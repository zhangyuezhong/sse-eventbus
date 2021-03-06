/**
 * Copyright 2016-2017 Ralph Schaer <ralphschaer@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ch.rasc.sse.eventbus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PreDestroy;

import org.springframework.context.event.EventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import ch.rasc.sse.eventbus.config.SseEventBusConfigurer;

public class SseEventBus {

	/**
	 * Client Id -> SseEmitter
	 */
	private final Map<String, Client> clients;

	/**
	 * EventName -> Collection of Client Ids
	 */
	private final Map<String, Set<String>> eventSubscribers;

	private final ScheduledExecutorService taskScheduler;

	private final int noOfSendResponseTries;

	private final Duration clientExpiration;

	private List<DataObjectConverter> dataObjectConverters;

	private final BlockingQueue<ClientEvent> errorQueue;

	private final BlockingQueue<ClientEvent> sendQueue;

	public SseEventBus(SseEventBusConfigurer configurer) {

		this.taskScheduler = configurer.taskScheduler();
		this.noOfSendResponseTries = configurer.noOfSendResponseTries();
		this.clientExpiration = configurer.clientExpiration();

		this.clients = new ConcurrentHashMap<>();
		this.eventSubscribers = new ConcurrentHashMap<>();

		this.errorQueue = configurer.errorQueue();
		this.sendQueue = configurer.sendQueue();

		this.taskScheduler.submit(this::eventLoop);
		this.taskScheduler.scheduleWithFixedDelay(this::reScheduleFailedEvents, 0,
				configurer.schedulerDelay().toMillis(), TimeUnit.MILLISECONDS);
		this.taskScheduler.scheduleAtFixedRate(this::cleanUpClients, 0,
				this.clientExpiration.toMillis(), TimeUnit.MILLISECONDS);
	}

	@PreDestroy
	public void cleanUp() {
		this.taskScheduler.shutdownNow();
	}

	public SseEmitter createSseEmitter(String clientId) {
		return createSseEmitter(clientId, 180_000L);
	}

	public SseEmitter createSseEmitter(String clientId, String... events) {
		return createSseEmitter(clientId, 180_000L, false, events);
	}

	public SseEmitter createSseEmitter(String clientId, boolean unsubscribe,
			String... events) {
		return createSseEmitter(clientId, 180_000L, unsubscribe, events);
	}

	public SseEmitter createSseEmitter(String clientId, Long timeout, String... events) {
		return createSseEmitter(clientId, timeout, false, events);
	}

	/**
	 * Creates a {@link SseEmitter} and registers the client in the internal database.
	 * Client will be subscribed to the provided events if specified.
	 *
	 * @param clientId unique client identifier
	 * @param timeout timeout value in milliseconds
	 * @param unsubscribe if true unsubscribes from all events that are not provided with
	 * the next parameter
	 * @param events events the client wants to subscribe
	 * @return a new SseEmitter instance
	 */
	public SseEmitter createSseEmitter(String clientId, Long timeout, boolean unsubscribe,
			String... events) {
		SseEmitter emitter = new SseEmitter(timeout);
		emitter.onTimeout(emitter::complete);
		registerClient(clientId, emitter);

		if (events != null && events.length > 0) {
			if (unsubscribe) {
				unsubscribeFromAllEvents(clientId, events);
			}
			for (String event : events) {
				subscribe(clientId, event);
			}
		}

		return emitter;
	}

	public void registerClient(String clientId, SseEmitter emitter) {
		Client client = this.clients.get(clientId);
		if (client == null) {
			this.clients.put(clientId, new Client(clientId, emitter));
		}
		else {
			client.updateEmitter(emitter);
		}
	}

	public void unregisterClient(String clientId) {
		unsubscribeFromAllEvents(clientId);
		this.clients.remove(clientId);
	}

	/**
	 * Subscribe to the default event (message)
	 */
	public void subscribe(String clientId) {
		subscribe(clientId, SseEvent.DEFAULT_EVENT);
	}

	public void subscribe(String clientId, String event) {
		this.eventSubscribers.computeIfAbsent(event, k -> new HashSet<>()).add(clientId);
	}

	/**
	 * Subscribe to the event and unbubscribe to all other currently subscribed events
	 */
	public void subscribeOnly(String clientId, String event) {
		this.eventSubscribers.computeIfAbsent(event, k -> new HashSet<>()).add(clientId);
		this.unsubscribeFromAllEvents(clientId, event);
	}

	public void unsubscribe(String clientId, String event) {
		Set<String> clientIds = this.eventSubscribers.get(event);
		if (clientIds != null) {
			clientIds.remove(clientId);
			if (clientIds.isEmpty()) {
				this.eventSubscribers.remove(event);
			}
		}
	}

	/**
	 * Unsubscribe the client from all events except the events provided with the
	 * keepEvents parameter. When keepEvents is null the client will be unsubscribed from
	 * all events
	 */
	public void unsubscribeFromAllEvents(String clientId, String... keepEvents) {
		Set<String> keepEventsSet = null;
		if (keepEvents != null && keepEvents.length > 0) {
			keepEventsSet = new HashSet<>();
			for (String keepEvent : keepEvents) {
				keepEventsSet.add(keepEvent);
			}
		}

		Set<String> emptyEvents = new HashSet<>();
		for (Map.Entry<String, Set<String>> entry : this.eventSubscribers.entrySet()) {
			if (keepEventsSet == null || !keepEventsSet.contains(entry.getKey())) {
				Set<String> clientIds = entry.getValue();
				clientIds.remove(clientId);
				if (clientIds.isEmpty()) {
					emptyEvents.add(entry.getKey());
				}
			}
		}

		emptyEvents.forEach(this.eventSubscribers::remove);
	}

	@EventListener
	public void handleEvent(SseEvent event) {
		try {

			String convertedValue = null;
			if (!(event.data() instanceof String)) {
				convertedValue = this.convertObject(event);
			}

			if (event.clientIds().isEmpty()) {
				for (Client client : this.clients.values()) {
					if (!event.excludeClientIds().contains(client.getId())
							&& isUserSubscribed(client.getId(), event)) {
						this.sendQueue
								.put(new ClientEvent(client, event, convertedValue));
					}
				}
			}
			else {
				for (String clientId : event.clientIds()) {
					if (isUserSubscribed(clientId, event)) {
						this.sendQueue.put(new ClientEvent(this.clients.get(clientId),
								event, convertedValue));
					}
				}
			}
		}
		catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private void reScheduleFailedEvents() {
		List<ClientEvent> failedEvents = new ArrayList<>();
		this.errorQueue.drainTo(failedEvents);

		for (ClientEvent sseClientEvent : failedEvents) {
			if (isUserSubscribed(sseClientEvent.getClient().getId(),
					sseClientEvent.getSseEvent())) {
				try {
					this.sendQueue.put(sseClientEvent);
				}
				catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}
		}
	}

	private boolean isUserSubscribed(String clientId, SseEvent event) {
		Set<String> subscribedClients = this.eventSubscribers.get(event.event());
		if (subscribedClients != null) {
			return subscribedClients.contains(clientId);
		}
		return false;
	}

	private void eventLoop() {
		try {
			while (true) {
				ClientEvent clientEvent = this.sendQueue.take();
				if (clientEvent.getErrorCounter() < this.noOfSendResponseTries) {
					Client client = clientEvent.getClient();
					boolean ok = sendEventToClient(clientEvent);
					if (ok) {
						client.updateLastTransfer();
					}
					else {
						clientEvent.incErrorCounter();
						this.errorQueue.put(clientEvent);
					}
				}
				else {
					this.unregisterClient(clientEvent.getClient().getId());
				}
			}
		}
		catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private static boolean sendEventToClient(ClientEvent clientEvent) {
		Client client = clientEvent.getClient();
		try {
			client.sseEmitter().send(clientEvent.createSseEventBuilder());
			return true;
		}
		catch (Exception e) {
			client.sseEmitter().completeWithError(e);
			return false;
		}

	}

	private String convertObject(SseEvent event) {
		if (this.dataObjectConverters != null) {
			for (DataObjectConverter converter : this.dataObjectConverters) {
				if (converter.supports(event)) {
					return converter.convert(event);
				}
			}
		}
		return null;
	}

	private void cleanUpClients() {
		if (!this.clients.isEmpty()) {
			long expirationTime = System.currentTimeMillis()
					- this.clientExpiration.toMillis();
			Iterator<Entry<String, Client>> it = this.clients.entrySet().iterator();
			Set<String> staleClients = new HashSet<>();
			while (it.hasNext()) {
				Entry<String, Client> entry = it.next();
				if (entry.getValue().lastTransfer() < expirationTime) {
					staleClients.add(entry.getKey());
				}
			}
			staleClients.forEach(this::unregisterClient);
		}
	}

	public List<DataObjectConverter> getDataObjectConverters() {
		return this.dataObjectConverters;
	}

	public void setDataObjectConverters(List<DataObjectConverter> dataObjectConverters) {
		this.dataObjectConverters = dataObjectConverters;
	}

}
