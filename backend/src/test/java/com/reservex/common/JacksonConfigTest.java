package com.reservex.common;

import com.reservex.config.ReserveXProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.xml.MappingJackson2XmlHttpMessageConverter;
import org.springframework.http.converter.yaml.MappingJackson2YamlHttpMessageConverter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JacksonConfigTest {

    @Test
    void removesTransitiveNonJsonJacksonConverters() {
        List<HttpMessageConverter<?>> converters = new ArrayList<>(List.of(
                new MappingJackson2HttpMessageConverter(),
                new MappingJackson2XmlHttpMessageConverter(),
                new MappingJackson2YamlHttpMessageConverter()));

        new JacksonConfig(new ReserveXProperties()).extendMessageConverters(converters);

        assertEquals(List.of(MappingJackson2HttpMessageConverter.class),
                converters.stream().map(Object::getClass).toList());
    }

    @Test
    void rejectsUnknownJsonProperties() {
        JacksonConfig config = new JacksonConfig(new ReserveXProperties());
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        config.reserveXJacksonCustomizer().customize(builder);

        assertThrows(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class,
                () -> builder.build().readValue("{\"name\":\"ok\",\"typo\":true}", Input.class));
    }

    private record Input(String name) {
    }
}
