package io.github.darkstarworks.pluginpulse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandRegistrationTest {

    @Test
    void stripsLeadingSlash() {
        assertEquals("tcp", CommandRegistration.normalize("/tcp"));
    }

    @Test
    void lowerCasesName() {
        assertEquals("tcp", CommandRegistration.normalize("TCP"));
    }

    @Test
    void keepsBareCommandOnly() {
        assertEquals("tcp", CommandRegistration.normalize("/tcp update check"));
    }

    @Test
    void trimsWhitespace() {
        assertEquals("mycmd", CommandRegistration.normalize("  /MyCmd  "));
    }
}
