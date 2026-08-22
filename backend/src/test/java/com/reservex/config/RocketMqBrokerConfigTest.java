package com.reservex.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RocketMqBrokerConfigTest {

    @Test
    void brokerUsesLocalSyncFlushWithoutPretendingToHaveReplication() throws IOException {
        String config = Files.readString(findRepositoryFile("docker", "rocketmq", "broker.conf"));

        assertThat(config).containsPattern("(?m)^\\s*flushDiskType\\s*=\\s*SYNC_FLUSH\\s*$");
        assertThat(config).containsPattern("(?m)^\\s*brokerRole\\s*=\\s*ASYNC_MASTER\\s*$");
        assertThat(config).containsPattern("(?m)^\\s*autoCreateTopicEnable\\s*=\\s*false\\s*$");
        assertThat(config).containsPattern("(?m)^\\s*authenticationEnabled\\s*=\\s*true\\s*$");
        assertThat(config).containsPattern("(?m)^\\s*authorizationEnabled\\s*=\\s*true\\s*$");
        assertThat(config).containsPattern("(?m)^\\s*migrateAuthFromV1Enabled\\s*=\\s*true\\s*$");
        assertThat(config).doesNotContainPattern("(?m)^\\s*aclEnable\\s*=");

        String entrypoint = Files.readString(findRepositoryFile(
                "docker", "rocketmq", "broker-entrypoint.sh"));
        assertThat(entrypoint).contains("AUTH_CONFIG_DIR=\"/home/rocketmq/store/config\"");
        assertThat(entrypoint).contains(
                "rm -rf \"${AUTH_CONFIG_DIR}/users\" \"${AUTH_CONFIG_DIR}/acls\"");

        String compose = Files.readString(findRepositoryFile("docker-compose.yml"));
        assertThat(compose).contains("WORKER_ID=${WORKER_ID:?WORKER_ID must be set explicitly (0..31)}");
        assertThat(compose).doesNotContain("WORKER_ID=${WORKER_ID:-0}");
    }

    private static Path findRepositoryFile(String... parts) {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current;
            for (String part : parts) {
                candidate = candidate.resolve(part);
            }
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("repository file not found: " + String.join("/", parts));
    }
}
