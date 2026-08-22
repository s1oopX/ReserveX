package com.reservex.bootstrap;

import com.reservex.config.ReserveXProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretGuardTest {

    @Test
    void enabledAdminBootstrapRejectsAnUnusablePasswordBeforeStartup() {
        ReserveXProperties props = new ReserveXProperties();
        props.getAdminBootstrap().setEnabled(true);
        props.getAdminBootstrap().setInitPassword("short");

        assertThrows(IllegalStateException.class, () -> new SecretGuard(props).assertSecrets());
    }
}
