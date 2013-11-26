/**
 * Copyright (C) 2013 Spotify AB
 */

package com.spotify.helios.agent;

import com.google.common.collect.Maps;

import com.spotify.helios.common.Reactor;
import com.spotify.helios.common.ReactorFactory;
import com.spotify.helios.common.descriptors.Goal;
import com.spotify.helios.common.descriptors.Job;
import com.spotify.helios.common.descriptors.JobId;
import com.spotify.helios.common.descriptors.Task;
import com.spotify.helios.common.descriptors.TaskStatus;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.Collections;
import java.util.Map;

import static com.spotify.helios.common.descriptors.Goal.START;
import static com.spotify.helios.common.descriptors.Goal.STOP;
import static com.spotify.helios.common.descriptors.TaskStatus.State.CREATING;
import static com.spotify.helios.common.descriptors.TaskStatus.State.RUNNING;
import static com.spotify.helios.common.descriptors.TaskStatus.State.STOPPED;
import static java.util.Arrays.asList;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AgentTest {

  @Mock AgentModel model;
  @Mock SupervisorFactory supervisorFactory;
  @Mock ReactorFactory reactorFactory;

  @Mock Supervisor fooSupervisor;
  @Mock Supervisor barSupervisor;
  @Mock Reactor reactor;

  @Captor ArgumentCaptor<Runnable> callbackCaptor;
  @Captor ArgumentCaptor<AgentModel.Listener> listenerCaptor;
  @Captor ArgumentCaptor<Long> timeoutCaptor;

  final Map<JobId, Task> jobs = Maps.newHashMap();
  final Map<JobId, Task> unmodifiableJobs = Collections.unmodifiableMap(jobs);

  final Map<JobId, TaskStatus> jobStatuses = Maps.newHashMap();
  final Map<JobId, TaskStatus> unmodifiableJobStatuses = Collections.unmodifiableMap(jobStatuses);

  Agent sut;
  Runnable callback;
  AgentModel.Listener listener;

  static final String FOO_JOB = "foojob";
  static final Job FOO_DESCRIPTOR = Job.newBuilder()
      .setCommand(asList("foo", "foo"))
      .setImage("foo:4711")
      .setName("foo")
      .setVersion("17")
      .build();

  static final String BAR_JOB = "barjob";
  static final Job BAR_DESCRIPTOR = Job.newBuilder()
      .setCommand(asList("bar", "bar"))
      .setImage("bar:5656")
      .setName("bar")
      .setVersion("63")
      .build();

  @Before
  public void setup() {
    when(supervisorFactory.create(FOO_DESCRIPTOR.getId(), FOO_DESCRIPTOR))
        .thenReturn(fooSupervisor);
    when(supervisorFactory.create(BAR_DESCRIPTOR.getId(), BAR_DESCRIPTOR))
        .thenReturn(barSupervisor);
    when(reactorFactory.create(callbackCaptor.capture(), timeoutCaptor.capture()))
        .thenReturn(reactor);
    when(model.getTasks()).thenReturn(unmodifiableJobs);
    when(model.getTaskStatuses()).thenReturn(unmodifiableJobStatuses);
    sut = new Agent(model, supervisorFactory, reactorFactory);
    verify(reactorFactory).create(any(Runnable.class), anyLong());
    callback = callbackCaptor.getValue();
  }

  private void startAgent() {
    sut.start();
    verify(model).addListener(listenerCaptor.capture());
    listener = listenerCaptor.getValue();
  }

  private void configure(final Job job, final Goal goal) {
    final Task task = new Task(job, goal);
    jobs.put(job.getId(), task);
  }

  private void start(Job descriptor) {
    configure(descriptor, START);
    callback.run();
  }

  private void stop(Job descriptor) {
    jobs.remove(descriptor.getId());
    callback.run();
  }

  @Test
  public void verifyReactorIsUpdatedWhenListenerIsCalled() {
    startAgent();
    listener.tasksChanged(model);
    verify(reactor).update();
  }

  @Test
  public void verifyAgentRecoversState() {
    configure(FOO_DESCRIPTOR, START);
    configure(BAR_DESCRIPTOR, STOP);

    jobStatuses.put(FOO_DESCRIPTOR.getId(),
                    new TaskStatus(FOO_DESCRIPTOR, CREATING, "foo-container-1", false));
    jobStatuses.put(BAR_DESCRIPTOR.getId(),
                    new TaskStatus(BAR_DESCRIPTOR, RUNNING, "bar-container-1", false));

    when(fooSupervisor.isStarting()).thenReturn(false);
    when(barSupervisor.isStarting()).thenReturn(true);

    startAgent();

    verify(supervisorFactory).create(FOO_DESCRIPTOR.getId(), FOO_DESCRIPTOR);
    verify(fooSupervisor).start();
    when(fooSupervisor.isStarting()).thenReturn(true);

    verify(supervisorFactory).create(BAR_DESCRIPTOR.getId(), BAR_DESCRIPTOR);
    verify(barSupervisor).stop();
    when(barSupervisor.isStarting()).thenReturn(false);

    callback.run();

    verify(fooSupervisor, times(1)).start();
    verify(barSupervisor, times(1)).stop();
  }

  @Test
  public void verifyAgentRecoversStateAndStopsUndesiredSupervisors() {
    jobStatuses.put(FOO_DESCRIPTOR.getId(),
                    new TaskStatus(FOO_DESCRIPTOR, CREATING, "foo-container-1", false));

    startAgent();

    // Verify that the undesired supervisor was created and then stopped
    verify(supervisorFactory).create(FOO_DESCRIPTOR.getId(), FOO_DESCRIPTOR);
    verify(fooSupervisor).stop();
  }

  @Test
  public void verifyAgentStartsSupervisors() {
    startAgent();

    start(FOO_DESCRIPTOR);
    verify(supervisorFactory).create(FOO_DESCRIPTOR.getId(), FOO_DESCRIPTOR);
    verify(fooSupervisor).start();
    when(fooSupervisor.isStarting()).thenReturn(true);

    start(BAR_DESCRIPTOR);
    verify(supervisorFactory).create(BAR_DESCRIPTOR.getId(), BAR_DESCRIPTOR);
    verify(barSupervisor).start();
    when(barSupervisor.isStarting()).thenReturn(true);

    callback.run();

    verify(fooSupervisor, times(1)).start();
    verify(barSupervisor, times(1)).start();
  }

  @Test
  public void verifyAgentStopsAndRecreatesSupervisors() {
    startAgent();

    // Verify that supervisor is stopped
    start(BAR_DESCRIPTOR);
    stop(BAR_DESCRIPTOR);
    verify(barSupervisor).stop();
    when(barSupervisor.getStatus()).thenReturn(STOPPED);

    // Verify that a new supervisor is created after the previous one is discarded
    callback.run();
    start(BAR_DESCRIPTOR);
    verify(supervisorFactory, times(2)).create(BAR_DESCRIPTOR.getId(), BAR_DESCRIPTOR);
    verify(barSupervisor, times(2)).start();
  }

  @Test
  public void verifyCloseDoesNotStopJobs() {
    startAgent();

    start(FOO_DESCRIPTOR);
    sut.close();
    verify(fooSupervisor).close();
    verify(fooSupervisor, never()).stop();
  }
}
