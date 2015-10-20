/*
 * Copyright (c) 2014 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.helios.client;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.escape.Escaper;
import com.google.common.net.UrlEscapers;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureFallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.spotify.helios.common.HeliosException;
import com.spotify.helios.common.Json;
import com.spotify.helios.common.Resolver;
import com.spotify.helios.common.Version;
import com.spotify.helios.common.VersionCompatibility;
import com.spotify.helios.common.descriptors.Deployment;
import com.spotify.helios.common.descriptors.DeploymentGroup;
import com.spotify.helios.common.descriptors.HostStatus;
import com.spotify.helios.common.descriptors.Job;
import com.spotify.helios.common.descriptors.JobId;
import com.spotify.helios.common.descriptors.JobStatus;
import com.spotify.helios.common.descriptors.RolloutOptions;
import com.spotify.helios.common.protocol.CreateDeploymentGroupResponse;
import com.spotify.helios.common.protocol.CreateJobResponse;
import com.spotify.helios.common.protocol.DeploymentGroupStatusResponse;
import com.spotify.helios.common.protocol.HostDeregisterResponse;
import com.spotify.helios.common.protocol.JobDeleteResponse;
import com.spotify.helios.common.protocol.JobDeployResponse;
import com.spotify.helios.common.protocol.JobUndeployResponse;
import com.spotify.helios.common.protocol.RemoveDeploymentGroupResponse;
import com.spotify.helios.common.protocol.RollingUpdateRequest;
import com.spotify.helios.common.protocol.RollingUpdateResponse;
import com.spotify.helios.common.protocol.SetGoalResponse;
import com.spotify.helios.common.protocol.TaskStatusEvents;
import com.spotify.helios.common.protocol.VersionResponse;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.transform;
import static com.google.common.util.concurrent.Futures.withFallback;
import static com.google.common.util.concurrent.MoreExecutors.getExitingExecutorService;
import static java.lang.String.format;
import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.TimeUnit.SECONDS;
import static com.spotify.helios.common.VersionCompatibility.HELIOS_SERVER_VERSION_HEADER;
import static com.spotify.helios.common.VersionCompatibility.HELIOS_VERSION_STATUS_HEADER;

public class HeliosClient implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(HeliosClient.class);

  private final String user;
  private final RequestDispatcher dispatcher;
  private final AtomicBoolean versionWarningLogged = new AtomicBoolean();

  public HeliosClient(final String user, final RequestDispatcher dispatcher) {
    this.user = checkNotNull(user);
    this.dispatcher = checkNotNull(dispatcher);
  }

  @Override
  public void close() {
    dispatcher.close();
  }

  private URI uri(final String path) {
    return uri(path, Collections.<String, String>emptyMap());
  }

  private URI uri(final String path, final Map<String, String> query) {
    // TODO(dano): use a uri builder and clean this mess up
    checkArgument(path.startsWith("/"));
    final Map<String, String> queryWithUser = Maps.newHashMap(query);
    queryWithUser.put("user", user);
    final String queryPart = Joiner.on('&').withKeyValueSeparator("=").join(queryWithUser);
    try {
      return new URI("http", "helios", path, queryPart, null);
    } catch (URISyntaxException e) {
      throw Throwables.propagate(e);
    }
  }

  private String path(final String resource, final Object... params) {
    final String path;
    final Escaper escaper = UrlEscapers.urlPathSegmentEscaper();
    if (params.length == 0) {
      path = resource;
    } else {
      final List<String> encodedParams = Lists.newArrayList();
      for (final Object param : params) {
        encodedParams.add(escaper.escape(param.toString()));
      }
      path = format(resource, encodedParams.toArray());
    }
    return path;
  }

  private ListenableFuture<Response> request(final URI uri, final String method) {
    return request(uri, method, null);
  }

  private ListenableFuture<Response> request(final URI uri, final String method,
                                             final Object entity) {
    final Map<String, List<String>> headers = Maps.newHashMap();
    final byte[] entityBytes;
    headers.put(VersionCompatibility.HELIOS_VERSION_HEADER,
                Collections.singletonList(Version.POM_VERSION));
    if (entity != null) {
      headers.put("Content-Type", singletonList("application/json"));
      headers.put("Charset", singletonList("utf-8"));
      entityBytes = Json.asBytesUnchecked(entity);
    } else {
      entityBytes = new byte[]{};
    }

    final ListenableFuture<Response> f = dispatcher.request(uri, method, entityBytes, headers);
    return transform(f, new Function<Response, Response>() {
      @Override
      public Response apply(final Response response) {
        checkProtocolVersionStatus(response);
        return response;
      }
    });
  }

  private void checkProtocolVersionStatus(final Response response) {
    final VersionCompatibility.Status versionStatus = getVersionStatus(response);
    if (versionStatus == null) {
      log.debug("Server didn't return a version header!");
      return; // shouldn't happen really
    }

    final String serverVersion = response.header(HELIOS_SERVER_VERSION_HEADER);
    if ((versionStatus == VersionCompatibility.Status.MAYBE) &&
        (versionWarningLogged.compareAndSet(false, true))) {
      log.warn("Your Helios client version [{}] is ahead of the server [{}].  This will"
               + " probably work ok but there is the potential for weird things.  If in doubt,"
               + " contact the Helios team if you think the cluster you're connecting to is out"
               + " of date and should be upgraded.", Version.POM_VERSION, serverVersion);
    }
  }

  private VersionCompatibility.Status getVersionStatus(final Response response) {
    final String status = response.header(HELIOS_VERSION_STATUS_HEADER);
    if (status != null) {
      return VersionCompatibility.Status.valueOf(status);
    }
    return null;
  }

  private <T> ListenableFuture<T> get(final URI uri, final TypeReference<T> typeReference) {
    return get(uri, Json.type(typeReference));
  }

  private <T> ListenableFuture<T> get(final URI uri, final Class<T> clazz) {
    return get(uri, Json.type(clazz));
  }

  private <T> ListenableFuture<T> get(final URI uri, final JavaType javaType) {
    return transform(request(uri, "GET"), new ConvertResponseToPojo<T>(javaType));
  }

  private ListenableFuture<Integer> put(final URI uri) {
    return status(request(uri, "PUT"));
  }

  public ListenableFuture<JobDeployResponse> deploy(final Deployment job, final String host) {
    return deploy(job, host, "");
  }

  public ListenableFuture<JobDeployResponse> deploy(final Deployment job, final String host,
                                                    final String token) {
    final Set<Integer> deserializeReturnCodes = ImmutableSet.of(HTTP_OK, HTTP_NOT_FOUND,
                                                                HTTP_BAD_METHOD,
                                                                HTTP_BAD_REQUEST,
                                                                HTTP_FORBIDDEN);
    return transform(request(uri(path("/hosts/%s/jobs/%s", host, job.getJobId()),
                                 ImmutableMap.of("token", token)),
                             "PUT", job),
                     ConvertResponseToPojo.create(JobDeployResponse.class, deserializeReturnCodes));
  }

  public ListenableFuture<SetGoalResponse> setGoal(final Deployment job, final String host) {
    return setGoal(job, host, "");
  }

  public ListenableFuture<SetGoalResponse> setGoal(final Deployment job, final String host,
                                                   final String token) {
    return transform(request(uri(path("/hosts/%s/jobs/%s", host, job.getJobId()),
                                 ImmutableMap.of("token", token)),
                             "PATCH", job),
                     ConvertResponseToPojo.create(SetGoalResponse.class,
                                                  ImmutableSet.of(HTTP_OK, HTTP_NOT_FOUND,
                                                                  HTTP_FORBIDDEN)));
  }

  private ListenableFuture<Integer> status(final ListenableFuture<Response> req) {
    return transform(req,
                     new Function<Response, Integer>() {
                       @Override
                       public Integer apply(final Response reply) {
                         return reply.status();
                       }
                     });
  }

  public ListenableFuture<Deployment> deployment(final String host, final JobId job) {
    return get(uri(path("/hosts/%s/jobs/%s", host, job)), Deployment.class);
  }

  public ListenableFuture<HostStatus> hostStatus(final String host) {
    return hostStatus(host, Collections.<String, String>emptyMap());
  }

  public ListenableFuture<HostStatus>
  hostStatus(final String host, final Map<String, String> queryParams) {
    return get(uri(path("/hosts/%s/status", host), queryParams), HostStatus.class);
  }

  public ListenableFuture<Map<String, HostStatus>> hostStatuses(final List<String> hosts) {
    return hostStatuses(hosts, Collections.<String, String>emptyMap());
  }

  public ListenableFuture<Map<String, HostStatus>>
  hostStatuses(final List<String> hosts, final Map<String, String> queryParams) {
    final ConvertResponseToPojo<Map<String, HostStatus>> converter = ConvertResponseToPojo.create(
        TypeFactory.defaultInstance().constructMapType(Map.class, String.class, HostStatus.class),
        ImmutableSet.of(HTTP_OK));

    return transform(request(uri("/hosts/statuses", queryParams), "POST", hosts), converter);
  }

  public ListenableFuture<Integer> registerHost(final String host, final String id) {
    return put(uri(path("/hosts/%s", host), ImmutableMap.of("id", id)));
  }

  public ListenableFuture<JobDeleteResponse> deleteJob(final JobId id) {
    return deleteJob(id, "");
  }

  public ListenableFuture<JobDeleteResponse> deleteJob(final JobId id, final String token) {
    return transform(request(uri(path("/jobs/%s", id),
                                 ImmutableMap.of("token", token)),
                             "DELETE"),
                     ConvertResponseToPojo.create(JobDeleteResponse.class,
                                                  ImmutableSet.of(HTTP_OK, HTTP_NOT_FOUND,
                                                                  HTTP_BAD_REQUEST,
                                                                  HTTP_FORBIDDEN)));
  }

  public ListenableFuture<JobUndeployResponse> undeploy(final JobId jobId, final String host) {
    return undeploy(jobId, host, "");
  }

  public ListenableFuture<JobUndeployResponse> undeploy(final JobId jobId, final String host,
                                                        final String token) {
    return transform(request(uri(path("/hosts/%s/jobs/%s", host, jobId),
                                 ImmutableMap.of("token", token)),
                             "DELETE"),
                     ConvertResponseToPojo.create(JobUndeployResponse.class,
                                                  ImmutableSet.of(HTTP_OK, HTTP_NOT_FOUND,
                                                                  HTTP_BAD_REQUEST,
                                                                  HTTP_FORBIDDEN)));
  }

  public ListenableFuture<HostDeregisterResponse> deregisterHost(final String host) {
    return transform(request(uri(path("/hosts/%s", host)), "DELETE"),
                     ConvertResponseToPojo.create(HostDeregisterResponse.class,
                                                  ImmutableSet.of(HTTP_OK, HTTP_NOT_FOUND)));
  }

  public ListenableFuture<List<String>> listHosts() {
    return get(uri("/hosts/"), new TypeReference<List<String>>() {
    });
  }

  public ListenableFuture<List<String>> listMasters() {
    return get(uri("/masters/"), new TypeReference<List<String>>() {
    });
  }

  public ListenableFuture<VersionResponse> version() {
    // Create a fallback in case we fail to connect to the master. Return null if this happens.
    // The transform below will handle this and return an appropriate error message to the caller.
    final ListenableFuture<Response> futureWithFallback = withFallback(
        request(uri("/version/"), "GET"),
        new FutureFallback<Response>() {
          @Override
          public ListenableFuture<Response> create(@NotNull Throwable t) throws Exception {
            return immediateFuture(null);
          }
        }
    );

    return transform(
        futureWithFallback,
        new AsyncFunction<Response, VersionResponse>() {
          @Override
          public ListenableFuture<VersionResponse> apply(@NotNull Response reply) throws Exception {
            final String masterVersion =
                reply == null ? "Unable to connect to master" :
                reply.status() == HTTP_OK ? Json.read(reply.payload(), String.class) :
                "Master replied with error code " + reply.status();

            return immediateFuture(new VersionResponse(Version.POM_VERSION, masterVersion));
          }
        });
  }

  public ListenableFuture<CreateJobResponse> createJob(final Job descriptor) {
    return transform(request(uri("/jobs/"), "POST", descriptor),
                     ConvertResponseToPojo.create(CreateJobResponse.class,
                                                  ImmutableSet.of(HTTP_OK, HTTP_BAD_REQUEST)));
  }

  public ListenableFuture<Map<JobId, Job>> jobs(final String query) {
    return get(uri("/jobs", ImmutableMap.of("q", query)), new TypeReference<Map<JobId, Job>>() {
    });
  }

  public ListenableFuture<Map<JobId, Job>> jobs() {
    return get(uri("/jobs"), new TypeReference<Map<JobId, Job>>() {});
  }

  public ListenableFuture<TaskStatusEvents> jobHistory(final JobId jobId) {
    return transform(
        request(uri(path("/history/jobs/%s", jobId.toString())), "GET"),
        ConvertResponseToPojo.create(TaskStatusEvents.class,
                                     ImmutableSet.of(HTTP_OK, HTTP_NOT_FOUND)));
  }

  public ListenableFuture<JobStatus> jobStatus(final JobId jobId) {
    return get(uri(path("/jobs/%s/status", jobId)), JobStatus.class);
  }

  public ListenableFuture<Map<JobId, JobStatus>> jobStatuses(final Set<JobId> jobs) {
    final ConvertResponseToPojo<Map<JobId, JobStatus>> converter = ConvertResponseToPojo.create(
        TypeFactory.defaultInstance().constructMapType(Map.class, JobId.class, JobStatus.class),
        ImmutableSet.of(HTTP_OK));
    
    return transform(request(uri("/jobs/statuses"), "POST", jobs), converter);
  }

  public ListenableFuture<DeploymentGroup> deploymentGroup(final String name) {
    return get(uri("/deployment-group/" + name), new TypeReference<DeploymentGroup>() {
    });
  }

  public ListenableFuture<List<String>> listDeploymentGroups() {
    return get(uri("/deployment-group/"), new TypeReference<List<String>>() {
    });
  }

  public ListenableFuture<DeploymentGroupStatusResponse> deploymentGroupStatus(final String name) {
    return get(uri(path("/deployment-group/%s/status", name)),
               new TypeReference<DeploymentGroupStatusResponse>() {});
  }

  public ListenableFuture<CreateDeploymentGroupResponse>
  createDeploymentGroup(final DeploymentGroup descriptor) {
    return transform(request(uri("/deployment-group/"), "POST", descriptor),
                     ConvertResponseToPojo.create(CreateDeploymentGroupResponse.class,
                                                  ImmutableSet.of(HTTP_OK, HTTP_BAD_REQUEST)));
  }

  public ListenableFuture<RemoveDeploymentGroupResponse> removeDeploymentGroup(final String name) {
    return transform(request(uri("/deployment-group/" + name), "DELETE"),
                     ConvertResponseToPojo.create(RemoveDeploymentGroupResponse.class,
                                                  ImmutableSet.of(HTTP_OK, HTTP_BAD_REQUEST)));
  }

  public ListenableFuture<RollingUpdateResponse> rollingUpdate(
      final String deploymentGroupName, final JobId job, final RolloutOptions options) {
    return transform(
        request(uri(path("/deployment-group/%s/rolling-update", deploymentGroupName)),
                "POST", new RollingUpdateRequest(job, options)),
        ConvertResponseToPojo.create(RollingUpdateResponse.class,
                                     ImmutableSet.of(HTTP_OK, HTTP_BAD_REQUEST)));
  }

  public ListenableFuture<Integer> stopDeploymentGroup(final String deploymentGroupName) {
    return status(request(
        uri(path("/deployment-group/%s/stop", deploymentGroupName)), "POST"));
  }

  private static final class ConvertResponseToPojo<T> implements AsyncFunction<Response, T> {

    private final JavaType javaType;
    private final Set<Integer> decodeableStatusCodes;

    private ConvertResponseToPojo(final JavaType javaType) {
      this(javaType, ImmutableSet.of(HTTP_OK));
    }

    public ConvertResponseToPojo(final JavaType type, final Set<Integer> decodeableStatusCodes) {
      this.javaType = type;
      this.decodeableStatusCodes = decodeableStatusCodes;
    }

    public static <T> ConvertResponseToPojo<T> create(final JavaType type,
                                                      final Set<Integer> decodeableStatusCodes) {
      return new ConvertResponseToPojo<>(type, decodeableStatusCodes);
    }

    public static <T> ConvertResponseToPojo<T> create(final Class<T> clazz,
                                                      final Set<Integer> decodeableStatusCodes) {
      return new ConvertResponseToPojo<>(Json.type(clazz), decodeableStatusCodes);
    }

    @Override
    public ListenableFuture<T> apply(@NotNull final Response reply)
        throws HeliosException {
      if (reply.status() == HTTP_NOT_FOUND && !decodeableStatusCodes.contains(HTTP_NOT_FOUND)) {
        return immediateFuture(null);
      }

      if (!decodeableStatusCodes.contains(reply.status())) {
        throw new HeliosException("request failed: " + reply);
      }

      if (reply.payload().length == 0) {
        throw new HeliosException("bad reply: " + reply);
      }

      final T result;
      try {
        result = Json.read(reply.payload(), javaType);
      } catch (IOException e) {
        throw new HeliosException("bad reply: " + reply, e);
      }

      return immediateFuture(result);
    }
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {

    private String user;
    private Supplier<List<URI>> endpointSupplier;

    public Builder setUser(final String user) {
      this.user = user;
      return this;
    }

    public Builder setDomain(final String domain) {
      return setEndpointSupplier(Resolver.supplier("helios", domain));
    }

    public Builder setEndpoints(final List<URI> endpoints) {
      return setEndpointSupplier(Suppliers.ofInstance(endpoints));
    }

    public Builder setEndpoints(final URI... endpoints) {
      return setEndpointSupplier(Suppliers.ofInstance(asList(endpoints)));
    }

    public Builder setEndpoints(final String... endpoints) {
      return setEndpointStrings(asList(endpoints));
    }

    public Builder setEndpointStrings(final List<String> endpoints) {
      final List<URI> uris = Lists.newArrayList();
      for (String endpoint : endpoints) {
        uris.add(URI.create(endpoint));
      }
      return setEndpoints(uris);
    }

    public Builder setEndpointSupplier(final Supplier<List<URI>> endpointSupplier) {
      this.endpointSupplier = endpointSupplier;
      return this;
    }

    public HeliosClient build() {
      return new HeliosClient(user, new DefaultRequestDispatcher(
          endpointSupplier,
          MoreExecutors.listeningDecorator(getExitingExecutorService(
              (ThreadPoolExecutor) newFixedThreadPool(4), 0, SECONDS))));
    }
  }

  /**
   * Create a new helios client as a specific user, connecting to a helios master cluster in a
   * specific domain.
   *
   * @param domain The target domain.
   * @param user   The user to identify as.
   * @return A helios client.
   */
  public static HeliosClient create(final String domain, final String user) {
    return HeliosClient.newBuilder()
        .setDomain(domain)
        .setUser(user)
        .build();
  }

}
