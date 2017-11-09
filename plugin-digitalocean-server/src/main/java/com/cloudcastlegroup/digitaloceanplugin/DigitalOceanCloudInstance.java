/*
 * Copyright 2009-2013 Cloud Castle Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cloudcastlegroup.digitaloceanplugin;

import com.cloudcastlegroup.digitaloceanplugin.apiclient.DigitalOceanApiProvider;
import com.cloudcastlegroup.digitaloceanplugin.apiclient.DigitalOceanApiUtils;
import com.myjeeva.digitalocean.common.ActionStatus;
import com.myjeeva.digitalocean.common.DropletStatus;
import com.myjeeva.digitalocean.pojo.Action;
import com.myjeeva.digitalocean.pojo.Droplet;
import jetbrains.buildServer.clouds.*;
import jetbrains.buildServer.serverSide.AgentDescription;
import jetbrains.buildServer.util.ExceptionUtil;
import jetbrains.buildServer.util.WaitFor;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static com.cloudcastlegroup.digitaloceanplugin.BuildAgentConfigurationConstants.IMAGE_ID_PARAM_NAME;
import static com.cloudcastlegroup.digitaloceanplugin.BuildAgentConfigurationConstants.INSTANCE_ID_PARAM_NAME;

/**
 * User: graf
 * Date: 10/12/13
 * Time: 17:00
 */
public class DigitalOceanCloudInstance implements CloudInstance {
  @NotNull
  private static final Logger LOG = Logger.getLogger(DigitalOceanCloudInstance.class);
  private static final int STATUS_WAITING_TIMEOUT = 15 * 60 * 1000;
  public static final int DROPLET_CREATING_TIMEOUT = 15 * 60 * 1000;
  public static final int DROPLET_STARTING_TIMEOUT = 15 * 60 * 1000;
  public static final int DROPLET_SHUTDOWN_TIMEOUT = 15 * 60 * 1000;

  @NotNull
  private final List<DropletLifecycleListener> myDropletLifecycleListeners = new LinkedList<DropletLifecycleListener>();

  @NotNull
  private final DigitalOceanCloudImage myImage;

  @NotNull
  private final DigitalOceanApiProvider myApi;

  private final int myDigitalOceanCloudImageId;
  private final int myDigitalOceanSshKeyId;
  private final String myDigitalOceanSizeId;
  private final String myDigitalOceanRegionId;

  @Nullable
  private Droplet myDroplet;

  @NotNull
  private volatile InstanceStatus myStatus;
  @Nullable
  private volatile CloudErrorInfo myErrorInfo;
  @NotNull
  private final Date myStartTime = new Date();

  @NotNull
  private final ExecutorService myExecutor;

  public DigitalOceanCloudInstance(@NotNull final DigitalOceanApiProvider api,
                                   @NotNull DigitalOceanCloudImage image,
                                   @NotNull ExecutorService executor,
                                   int sshKeyId, String regionId, String sizeId) {
    myImage = image;
    myStatus = InstanceStatus.SCHEDULED_TO_START;
    myExecutor = executor;
    myApi = api;

    myDigitalOceanRegionId = regionId;
    myDigitalOceanSizeId = sizeId;
    myDigitalOceanSshKeyId = sshKeyId;
    myDigitalOceanCloudImageId = myImage.getDigitalOceanImage().getId();
  }

  @NotNull
  public String getInstanceId() {
    if (myDroplet == null || DigitalOceanApiUtils.getIpAddress(myDroplet) == null) {
      return NamesFactory.getStartingInstanceId(myStartTime);
    } else {
      return NamesFactory.getInstanceId(DigitalOceanApiUtils.getIpAddress(myDroplet));
    }
  }

  @NotNull
  public String getName() {
    return NamesFactory.getBuildAgentName(myImage.getId(), getInstanceId());
  }

  @NotNull
  public String getImageId() {
    return myImage.getId();
  }

  @NotNull
  public DigitalOceanCloudImage getImage() {
    return myImage;
  }

  @NotNull
  public Date getStartedTime() {
    return myStartTime;
  }

  public String getNetworkIdentity() {
    if (myDroplet == null || DigitalOceanApiUtils.getIpAddress(myDroplet) == null) {
      return "0.0.0.0";
    } else {
      return DigitalOceanApiUtils.getIpAddress(myDroplet);
    }
  }

  @NotNull
  public InstanceStatus getStatus() {
    return myStatus;
  }

  @Nullable
  public CloudErrorInfo getErrorInfo() {
    return myErrorInfo;
  }

  public void setExistingDroplet(Droplet droplet) {
    myDroplet = droplet;
    myStatus = InstanceStatus.RUNNING;
  }

  /**
   * Check whether or not specified agent is running on this machine
   *
   * @param agentDescription agent
   * @return actually running
   */
  public boolean containsAgent(@NotNull final AgentDescription agentDescription) {
    final Map<String, String> configParams = agentDescription.getConfigurationParameters();
    return getInstanceId().equals(configParams.get(INSTANCE_ID_PARAM_NAME)) &&
            getImageId().equals(configParams.get(IMAGE_ID_PARAM_NAME));
  }

  /**
   * Starts instance asynchronously in another thread.
   *
   * @param data instance params
   */
  public void start(@NotNull CloudInstanceUserData data) {
    data.setAgentRemovePolicy(CloudConstants.AgentRemovePolicyValue.RemoveAgent);

    myStatus = InstanceStatus.STARTING;

    myExecutor.submit(ExceptionUtil.catchAll("Start Digital Ocean's cloud agent", new Runnable() {
      public void run() {
        try {
          doStart();
          myStatus = InstanceStatus.RUNNING;
        } catch (final Exception e) {
          processError(e);
        }
      }
    }));
  }

  public void restart() {
    new WaitFor(STATUS_WAITING_TIMEOUT) {
      @Override
      protected boolean condition() {
        return myStatus == InstanceStatus.RUNNING;
      }
    };
    myStatus = InstanceStatus.RESTARTING;
    try {
      doStop();
      Thread.sleep(15000);
      doStart();
    } catch (final Exception e) {
      processError(e);
    }
  }

  /**
   * Terminates instance synchronously. Blocks thread while instance is stopping.
   */
  public void terminate() {
    myStatus = InstanceStatus.STOPPING;
    try {
      doStop();
      myStatus = InstanceStatus.STOPPED;
      LOG.info("Droplet has been destroyed. You cannot use this instance anymore");
    } catch (final Exception e) {
      processError(e);
    }
  }

  private void processError(@NotNull final Exception e) {
    final String message = e.getMessage() == null ? e.toString() : e.getMessage();
    LOG.error(message, e);
    myErrorInfo = new CloudErrorInfo(message, message, e);
    myStatus = InstanceStatus.ERROR;
    for (DropletLifecycleListener listener : myDropletLifecycleListeners) {
      listener.onDropletError(myDroplet);
    }
  }

  private void doStart() throws Exception {
    createDroplet();
    powerOnDroplet();
  }

  private void doStop() throws Exception {
    powerOffDroplet();
    destroyDroplet();
  }

  private void powerOnDroplet() {
    if (myDroplet == null) {
      throw new IllegalStateException("Droplet does not exist");
    }

    final long startTime = System.currentTimeMillis();

    for (DropletLifecycleListener listener : myDropletLifecycleListeners) {
      listener.onDropletStarted(myDroplet);
    }

    final Action powerOnAction = myApi.powerOnDroplet(myDroplet.getId());
    waitForDigitalOceanAction(powerOnAction.getId(), DROPLET_STARTING_TIMEOUT);

    LOG.info("Droplet [" + myDroplet.getId() + "] started in " +
            ((System.currentTimeMillis() - startTime) / 1000.0) + " sec");
  }

  private void waitForDigitalOceanAction(final int actionId, final int timeout) {
    new WaitFor(timeout) {
      @Override
      protected boolean condition() {
        final Action action = myApi.getEvent(actionId);
        return action == null || action.getStatus().equals(ActionStatus.COMPLETED);
      }
    };
  }

  private void createDroplet() {
    final long startTime = System.currentTimeMillis();

    String name = "inst-" + Math.abs(new Date().hashCode());
    LOG.info("About to create droplet with name '" + name + "'");

    myDroplet = myApi.createDroplet(name, myDigitalOceanCloudImageId, myDigitalOceanSizeId,
            myDigitalOceanRegionId, myDigitalOceanSshKeyId);

    new WaitFor(DROPLET_CREATING_TIMEOUT) {
      @Override
      protected boolean condition() {
        final Droplet droplet = myApi.getDroplet(myDroplet.getId());
        return droplet.getStatus().equals(DropletStatus.ACTIVE);
      }
    };

    myDroplet = myApi.getDroplet(myDroplet.getId());

    final String ip = DigitalOceanApiUtils.getIpAddress(myDroplet);
    LOG.info("Droplet [" + myDroplet.getId() + "; " + "ip:" + ip + "] has been created in " + ((System.currentTimeMillis() - startTime) / 1000.0) + " sec");
  }

  private void destroyDroplet() {
    if (myDroplet == null) {
      return;
    }

    final long startTime = System.currentTimeMillis();

    myApi.destroyDroplet(myDroplet.getId());

    for (DropletLifecycleListener listener : myDropletLifecycleListeners) {
      listener.onDropletDestroyed(myDroplet);
    }

    LOG.info("Droplet [" + myDroplet.getId() + "] destroyed in " +
            ((System.currentTimeMillis() - startTime) / 1000.0) + " sec");
  }

  private void powerOffDroplet() {
    if (myDroplet == null) {
      throw new IllegalStateException("Droplet does not exist");
    }

    LOG.info("About to stop droplet with id=" + myDroplet.getId());

    final Action powerOffEvent = myApi.shutdownDroplet(myDroplet.getId());

    final long startTime = System.currentTimeMillis();
    waitForDigitalOceanAction(powerOffEvent.getId(), DROPLET_SHUTDOWN_TIMEOUT);

    LOG.info("Droplet [" + myDroplet.getId() + "] has been turned off in " +
            ((System.currentTimeMillis() - startTime) / 1000.0) + " sec");
  }

  public void addOnDropletReadyListener(@NotNull DropletLifecycleListener listener) {
    myDropletLifecycleListeners.add(listener);
  }

  @Nullable
  public Droplet getDigitalOceanDroplet() {
    return myDroplet;
  }
}
