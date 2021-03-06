/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2018-2020 The Feast Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feast.jobcontroller.service;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.beans.HasPropertyWithValue.hasProperty;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;
import static org.hamcrest.collection.IsMapContaining.hasEntry;
import static org.hamcrest.collection.IsMapWithSize.aMapWithSize;
import static org.hamcrest.core.AllOf.allOf;
import static org.hamcrest.number.OrderingComparison.greaterThan;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.InvalidProtocolBufferException;
import feast.common.it.*;
import feast.common.util.KafkaSerialization;
import feast.jobcontroller.config.FeastProperties;
import feast.jobcontroller.dao.JobRepository;
import feast.jobcontroller.model.Job;
import feast.jobcontroller.model.JobStatus;
import feast.jobcontroller.runner.JobManager;
import feast.proto.core.*;
import feast.proto.types.ValueProto;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.SocketUtils;

@SpringBootTest(
    properties = {
      "feast.jobs.enabled=true",
      "feast.jobs.polling_interval_milliseconds=1000",
      "feast.stream.specsOptions.notifyIntervalMilliseconds=1000",
      "feast.jobs.controller.consolidate-jobs-per-source=true",
      "feast.jobs.controller.feature-set-selector[0].name=test",
      "feast.jobs.controller.feature-set-selector[0].project=default",
      "feast.jobs.controller.whitelisted-stores[0]=test-store",
      "feast.jobs.controller.whitelisted-stores[1]=new-store",
      "feast.version=1.0.0"
    })
public class JobControllerIT extends BaseIT {
  @Autowired private FakeJobManager jobManager;

  @Autowired private JobRepository jobRepository;

  @Autowired KafkaTemplate<String, IngestionJobProto.FeatureSetSpecAck> ackPublisher;

  static CoreServiceGrpc.CoreServiceBlockingStub stub;
  static List<FeatureSetProto.FeatureSetSpec> specsMailbox = new ArrayList<>();
  static SimpleCoreClient coreApiClient;
  static SimpleJcClient apiClient;

  static int corePort = SocketUtils.findAvailableTcpPort();
  static ExternalApp coreApp =
      ExternalApp.builder()
          .setSpringApplication(feast.core.CoreApplication.class)
          .setName("it-core")
          .setGRPCPort(corePort)
          .setPostgreSQL(postgreSQLContainer)
          .build();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("feast.core-port", () -> corePort);
  }

  @BeforeAll
  public static void globalSetUp(@Value("${grpc.server.port}") int localPort) {
    coreApp.start();

    ManagedChannel channel =
        ManagedChannelBuilder.forAddress("localhost", corePort).usePlaintext().build();
    stub = CoreServiceGrpc.newBlockingStub(channel);
    coreApiClient = new SimpleCoreClient(stub);

    apiClient =
        new SimpleJcClient(
            JobControllerServiceGrpc.newBlockingStub(
                ManagedChannelBuilder.forAddress("localhost", localPort).usePlaintext().build()));
  }

  @AfterAll
  public static void globalTearDown() {
    coreApp.stop();
  }

  @BeforeEach
  public void setUp(TestInfo testInfo) {
    coreApiClient.updateStore(DataGenerator.getDefaultStore());

    specsMailbox = new ArrayList<>();

    if (!isSequentialTest(testInfo)) {
      jobManager.cleanAll();
      jobRepository.deleteAll();
    }
  }

  @KafkaListener(
      topics = {"${feast.stream.specsOptions.specsTopic}"},
      containerFactory = "testListenerContainerFactory")
  public void listenSpecs(ConsumerRecord<String, byte[]> record)
      throws InvalidProtocolBufferException {
    FeatureSetProto.FeatureSetSpec featureSetSpec =
        FeatureSetProto.FeatureSetSpec.parseFrom(record.value());
    specsMailbox.add(featureSetSpec);
  }

  @Test
  @SneakyThrows
  public void shouldCreateJobForNewSource() {
    coreApiClient.simpleApplyFeatureSet(
        DataGenerator.createFeatureSet(DataGenerator.getDefaultSource(), "default", "test"));

    List<FeatureSetProto.FeatureSet> featureSets = coreApiClient.simpleListFeatureSets("*");
    assertThat(featureSets.size(), equalTo(1));

    await()
        .until(
            jobManager::getAllJobs,
            containsInAnyOrder(
                allOf(
                    hasProperty("id", containsString("kafka-1422433213")),
                    hasProperty("stores", aMapWithSize(1)),
                    hasProperty("featureSetDeliveryStatuses", aMapWithSize(1)))));

    // verify stay stable
    Job job = jobManager.getAllJobs().get(0);
    Thread.sleep(3000);

    assertThat(
        jobManager.getAllJobs(), containsInAnyOrder(hasProperty("id", equalTo(job.getId()))));
  }

  @Test
  public void shouldUpgradeJobWhenStoreChanged() {
    coreApiClient.simpleApplyFeatureSet(
        DataGenerator.createFeatureSet(DataGenerator.getDefaultSource(), "default", "test"));

    await().until(jobManager::getAllJobs, hasSize(1));

    coreApiClient.updateStore(
        DataGenerator.createStore(
            "new-store",
            StoreProto.Store.StoreType.REDIS,
            ImmutableList.of(DataGenerator.getDefaultSubscription())));

    await()
        .until(
            jobManager::getAllJobs,
            containsInAnyOrder(
                allOf(
                    hasProperty("stores", aMapWithSize(2)),
                    hasProperty("featureSetDeliveryStatuses", aMapWithSize(1)))));

    await().until(jobManager::getAllJobs, hasSize(1));
  }

  @Test
  public void shouldRestoreJobThatStopped() {
    coreApiClient.simpleApplyFeatureSet(
        DataGenerator.createFeatureSet(DataGenerator.getDefaultSource(), "default", "test"));

    await().until(() -> jobRepository.findByStatus(JobStatus.RUNNING), hasSize(1));
    Job job = jobRepository.findByStatus(JobStatus.RUNNING).get(0);

    List<IngestionJobProto.IngestionJob> ingestionJobs = apiClient.listIngestionJobs();
    assertThat(ingestionJobs, hasSize(1));
    assertThat(ingestionJobs, containsInAnyOrder(hasProperty("id", equalTo(job.getId()))));

    apiClient.restartIngestionJob(ingestionJobs.get(0).getId());

    await().until(() -> jobManager.getJobStatus(job), equalTo(JobStatus.ABORTED));

    await()
        .until(
            apiClient::listIngestionJobs,
            hasItem(
                allOf(
                    hasProperty("status", equalTo(IngestionJobProto.IngestionJobStatus.RUNNING)),
                    hasProperty("id", not(ingestionJobs.get(0).getId())))));
  }

  @Test
  @SneakyThrows
  public void shouldNotCreateJobForUnwantedFeatureSet() {
    coreApiClient.simpleApplyFeatureSet(
        DataGenerator.createFeatureSet(DataGenerator.getDefaultSource(), "default", "other"));

    Thread.sleep(2000);

    assertThat(jobManager.getAllJobs(), hasSize(0));
  }

  @Test
  @SneakyThrows
  public void shouldRestartJobWithOldVersion() {
    coreApiClient.simpleApplyFeatureSet(
        DataGenerator.createFeatureSet(DataGenerator.getDefaultSource(), "default", "test"));

    Job job =
        Job.builder()
            .setSource(DataGenerator.getDefaultSource())
            .setStores(
                ImmutableMap.of(
                    DataGenerator.getDefaultStore().getName(), DataGenerator.getDefaultStore()))
            .setId("some-running-id")
            .setLabels(ImmutableMap.of(JobControllerService.VERSION_LABEL, "0-9-9"))
            .build();

    jobManager.startJob(job);
    jobRepository.add(job);

    await().until(() -> jobManager.getJobStatus(job), equalTo(JobStatus.ABORTED));

    Job replacement = jobRepository.findByStatus(JobStatus.RUNNING).get(0);
    assertThat(replacement.getSource(), equalTo(job.getSource()));
    assertThat(replacement.getStores(), equalTo(job.getStores()));
    assertThat(replacement.getLabels(), hasEntry(JobControllerService.VERSION_LABEL, "1-0-0"));
  }

  @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
  @Nested
  class SpecNotificationFlow extends SequentialFlow {
    Job job;

    @AfterAll
    public void tearDown() {
      jobManager.cleanAll();
      jobRepository.deleteAll();
    }

    @Test
    @Order(1)
    public void shouldSendNewSpec() {
      jobManager.cleanAll();
      jobRepository.deleteAll();

      job =
          Job.builder()
              .setSource(DataGenerator.getDefaultSource())
              .setStores(
                  ImmutableMap.of(
                      DataGenerator.getDefaultStore().getName(), DataGenerator.getDefaultStore()))
              .setId("some-running-id")
              .setLabels(ImmutableMap.of(JobControllerService.VERSION_LABEL, "1-0-0"))
              .build();

      jobManager.startJob(job);
      jobRepository.add(job);

      coreApiClient.simpleApplyFeatureSet(
          DataGenerator.createFeatureSet(
              DataGenerator.getDefaultSource(),
              "default",
              "test",
              ImmutableMap.of("entity", ValueProto.ValueType.Enum.BOOL),
              ImmutableMap.of()));

      FeatureSetProto.FeatureSet featureSet = coreApiClient.simpleGetFeatureSet("default", "test");

      assertThat(
          featureSet.getMeta().getStatus(),
          equalTo(FeatureSetProto.FeatureSetStatus.STATUS_PENDING));

      await().until(() -> specsMailbox, hasSize(1));

      assertThat(
          specsMailbox.get(0),
          allOf(
              hasProperty("project", equalTo("default")),
              hasProperty("name", equalTo("test")),
              hasProperty("entitiesList", hasSize(1)),
              hasProperty("version", equalTo(1))));

      assertThat(jobRepository.findByStatus(JobStatus.RUNNING), hasSize(1));
    }

    @Test
    @Order(2)
    public void shouldUpdateSpec() {
      coreApiClient.simpleApplyFeatureSet(
          DataGenerator.createFeatureSet(
              DataGenerator.getDefaultSource(),
              "default",
              "test",
              ImmutableMap.of("entity", ValueProto.ValueType.Enum.BOOL),
              ImmutableMap.of("feature", ValueProto.ValueType.Enum.INT32)));

      await().until(() -> specsMailbox, hasSize(1));

      assertThat(
          specsMailbox.get(0),
          allOf(
              hasProperty("project", equalTo("default")),
              hasProperty("name", equalTo("test")),
              hasProperty("featuresList", hasSize(1)),
              hasProperty("version", equalTo(2))));
    }

    @Test
    @Order(3)
    public void shouldIgnoreOutdatedACKs() throws InterruptedException {
      ackPublisher.sendDefault(
          "default/test",
          IngestionJobProto.FeatureSetSpecAck.newBuilder()
              .setFeatureSetVersion(1)
              .setJobName(job.getId())
              .setFeatureSetReference("default/test")
              .build());

      // time to process
      Thread.sleep(1000);

      FeatureSetProto.FeatureSet featureSet = coreApiClient.simpleGetFeatureSet("default", "test");

      assertThat(
          featureSet.getMeta().getStatus(),
          equalTo(FeatureSetProto.FeatureSetStatus.STATUS_PENDING));
    }

    @Test
    @Order(4)
    public void shouldUpdateDeliveryStatus() {
      ackPublisher.sendDefault(
          "default/test",
          IngestionJobProto.FeatureSetSpecAck.newBuilder()
              .setFeatureSetVersion(2)
              .setJobName(job.getId())
              .setFeatureSetReference("default/test")
              .build());

      await()
          .until(
              () -> coreApiClient.simpleGetFeatureSet("default", "test").getMeta().getStatus(),
              equalTo(FeatureSetProto.FeatureSetStatus.STATUS_READY));
    }

    @Test
    @Order(5)
    public void shouldReallocateFeatureSetAfterSourceChanged() {
      assertThat(jobManager.getJobStatus(job), equalTo(JobStatus.RUNNING));

      coreApiClient.simpleApplyFeatureSet(
          DataGenerator.createFeatureSet(
              DataGenerator.createSource("localhost", "newTopic"),
              "default",
              "test",
              ImmutableMap.of("entity", ValueProto.ValueType.Enum.BOOL),
              ImmutableMap.of("feature", ValueProto.ValueType.Enum.INT32)));

      await().until(() -> jobManager.getJobStatus(job), equalTo(JobStatus.ABORTED));

      await().until(() -> jobRepository.findByStatus(JobStatus.RUNNING), hasSize(1));

      await().until(() -> specsMailbox, hasSize(greaterThan(0)));

      assertThat(
          specsMailbox.get(0),
          allOf(
              hasProperty("project", equalTo("default")),
              hasProperty("name", equalTo("test")),
              hasProperty("version", equalTo(3))));
    }

    @Test
    @Order(6)
    public void shouldUpdateStatusAfterACKFromNewJob() {
      job = jobRepository.findByStatus(JobStatus.RUNNING).get(0);

      ackPublisher.sendDefault(
          "default/test",
          IngestionJobProto.FeatureSetSpecAck.newBuilder()
              .setFeatureSetVersion(3)
              .setJobName(job.getId())
              .setFeatureSetReference("default/test")
              .build());

      await()
          .until(
              () -> coreApiClient.simpleGetFeatureSet("default", "test").getMeta().getStatus(),
              equalTo(FeatureSetProto.FeatureSetStatus.STATUS_READY));
    }
  }

  @TestConfiguration
  public static class TestConfig extends BaseIT.BaseTestConfig {
    @Bean
    public JobManager getJobManager() {
      return new FakeJobManager();
    }

    @Bean
    public KafkaTemplate<String, IngestionJobProto.FeatureSetSpecAck> specAckKafkaTemplate(
        FeastProperties feastProperties) {
      FeastProperties.StreamProperties streamProperties = feastProperties.getStream();
      Map<String, Object> props = new HashMap<>();

      props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());

      KafkaTemplate<String, IngestionJobProto.FeatureSetSpecAck> t =
          new KafkaTemplate<>(
              new DefaultKafkaProducerFactory<>(
                  props, new StringSerializer(), new KafkaSerialization.ProtoSerializer<>()));
      t.setDefaultTopic(streamProperties.getSpecsOptions().getSpecsAckTopic());
      return t;
    }
  }
}
