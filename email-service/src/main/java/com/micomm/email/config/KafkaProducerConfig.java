package com.micomm.email.config;

import com.micomm.email.kafka.EmailSendMessage;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, EmailSendMessage> emailSendProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            // How long a send waits for Kafka before failing (Kafka's own default is 60 s,
            // which leaves API callers hanging when Kafka is down).
            @Value("${micomm.email.kafka-max-block-ms:10000}") int maxBlockMs) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMs);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, EmailSendMessage> emailSendKafkaTemplate(
            ProducerFactory<String, EmailSendMessage> emailSendProducerFactory) {
        return new KafkaTemplate<>(emailSendProducerFactory);
    }
}