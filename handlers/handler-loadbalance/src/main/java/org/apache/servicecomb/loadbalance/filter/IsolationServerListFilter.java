/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.servicecomb.loadbalance.filter;

import java.util.ArrayList;
import java.util.List;

import org.apache.servicecomb.foundation.common.event.AlarmEvent.Type;
import org.apache.servicecomb.foundation.common.event.EventManager;
import org.apache.servicecomb.loadbalance.Configuration;
import org.apache.servicecomb.loadbalance.CseServer;
import org.apache.servicecomb.loadbalance.ServerListFilterExt;
import org.apache.servicecomb.loadbalance.event.IsolationServerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.eventbus.EventBus;
import com.netflix.loadbalancer.LoadBalancerStats;
import com.netflix.loadbalancer.Server;
import com.netflix.loadbalancer.ServerStats;

public final class IsolationServerListFilter implements ServerListFilterExt {

  private static final Logger LOGGER = LoggerFactory.getLogger(IsolationServerListFilter.class);

  private static final double PERCENT = 100;

  private String microserviceName;

  private int errorThresholdPercentage;

  private long singleTestTime;

  private long enableRequestThreshold;

  private int continuousFailureThreshold;

  private LoadBalancerStats stats;

  public EventBus eventBus = EventManager.getEventBus();

  public void setLoadBalancerStats(LoadBalancerStats stats) {
    this.stats = stats;
  }

  public LoadBalancerStats getLoadBalancerStats() {
    return stats;
  }

  public String getMicroserviceName() {
    return microserviceName;
  }

  public void setMicroserviceName(String microserviceName) {
    this.microserviceName = microserviceName;
  }

  @Override
  public List<Server> getFilteredListOfServers(List<Server> servers) {
    if (!Configuration.INSTANCE.isIsolationFilterOpen(microserviceName)) {
      return servers;
    }

    List<Server> filteredServers = new ArrayList<>();
    for (Server server : servers) {
      if (allowVisit(server)) {
        filteredServers.add(server);
      }
    }
    return filteredServers;
  }

  private void updateSettings() {
    errorThresholdPercentage = Configuration.INSTANCE.getErrorThresholdPercentage(microserviceName);
    singleTestTime = Configuration.INSTANCE.getSingleTestTime(microserviceName);
    enableRequestThreshold = Configuration.INSTANCE.getEnableRequestThreshold(microserviceName);
    continuousFailureThreshold = Configuration.INSTANCE.getContinuousFailureThreshold(microserviceName);
  }

  private boolean allowVisit(Server server) {
    updateSettings();
    ServerStats serverStats = stats.getSingleServerStat(server);
    long totalRequest = serverStats.getTotalRequestsCount();
    long failureRequest = serverStats.getSuccessiveConnectionFailureCount();
    int currentCountinuousFailureCount = 0;
    double currentErrorThresholdPercentage = 0;
    if (totalRequest < enableRequestThreshold) {
      return true;
    }

    if (continuousFailureThreshold > 0) {
      // continuousFailureThreshold has higher priority to decide the result
      currentCountinuousFailureCount = ((CseServer) server).getCountinuousFailureCount();
      if (currentCountinuousFailureCount < continuousFailureThreshold) {
        return true;
      }
    } else {
      // if continuousFailureThreshold, then check error percentage
      currentErrorThresholdPercentage = (failureRequest / (double) totalRequest) * PERCENT;
      if (currentErrorThresholdPercentage < errorThresholdPercentage) {
        return true;
      }
    }

    if ((System.currentTimeMillis() - ((CseServer) server).getLastVisitTime()) > singleTestTime) {
      LOGGER.info("The Service {}'s instance {} has been break, will give a single test opportunity.",
          microserviceName,
          server);
      eventBus.post(new IsolationServerEvent(microserviceName, totalRequest, currentCountinuousFailureCount,
          currentErrorThresholdPercentage,
          continuousFailureThreshold, errorThresholdPercentage, enableRequestThreshold,
          singleTestTime, Type.CLOSE));
      return true;
    }

    LOGGER.warn("The Service {}'s instance {} has been break!", microserviceName, server);
    eventBus.post(new IsolationServerEvent(microserviceName, totalRequest, currentCountinuousFailureCount,
        currentErrorThresholdPercentage,
        continuousFailureThreshold, errorThresholdPercentage, enableRequestThreshold,
        singleTestTime, Type.OPEN));
    return false;
  }
}
