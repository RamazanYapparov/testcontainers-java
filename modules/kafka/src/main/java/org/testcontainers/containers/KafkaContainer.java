package org.testcontainers.containers;

import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import lombok.SneakyThrows;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.TestcontainersConfiguration;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This container wraps Confluent Kafka and Zookeeper (optionally)
 *
 */
public class KafkaContainer extends GenericContainer<KafkaContainer> {

    private static final String STARTER_SCRIPT = "/testcontainers_start.sh";

    public static final int KAFKA_PORT = 9093;

    public static final int ZOOKEEPER_PORT = 2181;

    public static final String DEFAULT_INTERNAL_TOPIC_RF = "1";

    public static final String CONFLUENT_PLATFORM_VERSION = "5.2.1";

    private static final int PORT_NOT_ASSIGNED = -1;

    protected String externalZookeeperConnect = null;

    private int port = PORT_NOT_ASSIGNED;

    private boolean useImplicitNetwork = true;

    public KafkaContainer() {
        this(CONFLUENT_PLATFORM_VERSION);
    }

    public KafkaContainer(String confluentPlatformVersion) {
        super(TestcontainersConfiguration.getInstance().getKafkaImage() + ":" + confluentPlatformVersion);

        super.withNetwork(Network.SHARED);
        withExposedPorts(KAFKA_PORT);

        // Use two listeners with different names, it will force Kafka to communicate with itself via internal
        // listener when KAFKA_INTER_BROKER_LISTENER_NAME is set, otherwise Kafka will try to use the advertised listener
        withEnv("KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:" + KAFKA_PORT + ",BROKER://0.0.0.0:9092");
        withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "BROKER:PLAINTEXT,PLAINTEXT:PLAINTEXT");
        withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "BROKER");

        withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", DEFAULT_INTERNAL_TOPIC_RF);
        withEnv("KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS", DEFAULT_INTERNAL_TOPIC_RF);
        withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", DEFAULT_INTERNAL_TOPIC_RF);
        withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", DEFAULT_INTERNAL_TOPIC_RF);
        withEnv("KAFKA_LOG_FLUSH_INTERVAL_MESSAGES", Long.MAX_VALUE + "");
        withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0");
    }

    @Override
    public KafkaContainer withNetwork(Network network) {
        useImplicitNetwork = false;
        return super.withNetwork(network);
    }

    @Override
    public Network getNetwork() {
        if (useImplicitNetwork) {
            // TODO Only for backward compatibility, to be removed soon
            logger().warn(
                "Deprecation warning! " +
                    "KafkaContainer#getNetwork without an explicitly set network. " +
                    "Consider using KafkaContainer#withNetwork",
                new Exception("Deprecated method")
            );
        }
        return super.getNetwork();
    }

    public KafkaContainer withEmbeddedZookeeper() {
        externalZookeeperConnect = null;
        return self();
    }

    public KafkaContainer withExternalZookeeper(String connectString) {
        externalZookeeperConnect = connectString;
        return self();
    }

    public String getBootstrapServers() {
        if (port == PORT_NOT_ASSIGNED) {
            throw new IllegalStateException("You should start Kafka container first");
        }
        return String.format("PLAINTEXT://%s:%s", getContainerIpAddress(), port);
    }

    @Override
    protected void doStart() {
        withCommand("sh", "-c", "while [ ! -f " + STARTER_SCRIPT + " ]; do sleep 0.1; done; " + STARTER_SCRIPT);

        if (externalZookeeperConnect == null) {
            addExposedPort(ZOOKEEPER_PORT);
        }

        super.doStart();
    }

    @Override
    @SneakyThrows
    protected void containerIsStarting(InspectContainerResponse containerInfo, boolean reused) {
        super.containerIsStarting(containerInfo, reused);

        port = getMappedPort(KAFKA_PORT);

        if (reused) {
            return;
        }

        final String zookeeperConnect;
        if (externalZookeeperConnect != null) {
            zookeeperConnect = externalZookeeperConnect;
        } else {
            zookeeperConnect = startZookeeper();
        }

        String command = "#!/bin/bash \n";
        command += "export KAFKA_ZOOKEEPER_CONNECT='" + zookeeperConnect + "'\n";
        command += "export KAFKA_ADVERTISED_LISTENERS='" + Stream
            .concat(
                Stream.of(getBootstrapServers()),
                containerInfo.getNetworkSettings().getNetworks().values().stream()
                    .map(it -> "BROKER://" + it.getIpAddress() + ":9092")
            )
            .collect(Collectors.joining(",")) + "'\n";

        command += ". /etc/confluent/docker/bash-config \n";
        command += "/etc/confluent/docker/configure \n";
        command += "/etc/confluent/docker/launch \n";

        copyFileToContainer(
            Transferable.of(command.getBytes(StandardCharsets.UTF_8), 700),
            STARTER_SCRIPT
        );
    }

    @SneakyThrows(InterruptedException.class)
    private String startZookeeper() {
        ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(getContainerId())
            .withCmd("sh", "-c", "" +
                "printf 'clientPort=" + ZOOKEEPER_PORT + "\ndataDir=/var/lib/zookeeper/data\ndataLogDir=/var/lib/zookeeper/log' > /zookeeper.properties\n" +
                "zookeeper-server-start /zookeeper.properties\n"
            )
            .exec();

        dockerClient.execStartCmd(execCreateCmdResponse.getId()).exec(new ExecStartResultCallback()).awaitStarted(10, TimeUnit.SECONDS);

        return "localhost:" + ZOOKEEPER_PORT;
    }
}
