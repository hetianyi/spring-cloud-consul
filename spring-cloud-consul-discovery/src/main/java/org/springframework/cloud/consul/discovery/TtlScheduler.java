/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.consul.discovery;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.agent.model.NewService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.cloud.consul.serviceregistry.ConsulRegistration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

/**
 * Created by nicu on 11.03.2015.
 *
 * @author Stéphane LEROY
 */
public class TtlScheduler {

	private static final Log log = LogFactory.getLog(ConsulDiscoveryClient.class);

	private final Map<String, ScheduledFuture> serviceHeartbeats = new ConcurrentHashMap<>();

	private final TaskScheduler scheduler = new ConcurrentTaskScheduler(
			Executors.newSingleThreadScheduledExecutor());

	private HeartbeatProperties configuration;

	private ConsulClient client;

	private ConsulRegistration reg;

	private ConsulDiscoveryProperties properties;

	public TtlScheduler(HeartbeatProperties configuration, ConsulClient client) {
		this.configuration = configuration;
		this.client = client;
	}

	@Deprecated
	public void add(final NewService service) {
		add(service.getId());
	}

	public void add(final ConsulRegistration reg, ConsulDiscoveryProperties properties) {
		this.reg = reg;
		this.properties = properties;
		add(reg.getInstanceId());
	}

	/**
	 * Add a service to the checks loop.
	 * @param instanceId instance id
	 */
	public void add(String instanceId) {
		ScheduledFuture task = this.scheduler.scheduleAtFixedRate(
				new ConsulHeartbeatTask(instanceId),
				this.configuration.computeHearbeatInterval().toMillis());
		ScheduledFuture previousTask = this.serviceHeartbeats.put(instanceId, task);
		if (previousTask != null) {
			previousTask.cancel(true);
		}
	}

	public void remove(String instanceId) {
		ScheduledFuture task = this.serviceHeartbeats.get(instanceId);
		if (task != null) {
			task.cancel(true);
		}
		this.serviceHeartbeats.remove(instanceId);
	}

	private class ConsulHeartbeatTask implements Runnable {

		private String checkId;

		ConsulHeartbeatTask(String serviceId) {
			this.checkId = serviceId;
			if (!this.checkId.startsWith("service:")) {
				this.checkId = "service:" + this.checkId;
			}
		}

		@Override
		public void run() {
			try {
				TtlScheduler.this.client.agentCheckPass(this.checkId);
			}
			catch (Exception e) {
				// try to re-register service, this may fail again.
				try {
					TtlScheduler.this.client.agentServiceRegister(reg.getService(),
							TtlScheduler.this.properties.getAclToken());
					if (log.isDebugEnabled()) {
						log.debug("Agent check failed for " + this.checkId
								+ ", re-registered");
					}
				}
				finally {
					throw e;
				}
			}
			if (log.isDebugEnabled()) {
				log.debug("Sending consul heartbeat for: " + this.checkId);
			}
		}

	}

}
