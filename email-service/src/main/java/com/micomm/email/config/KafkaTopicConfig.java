package com.micomm.email.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Chunks of a send are spread over the send topic's partitions, and each partition is consumed
 * by one listener thread, so the partition count caps how many chunks run at once across all
 * email-service instances. Spring's KafkaAdmin creates the topic on startup, or adds partitions
 * to an existing topic that has fewer (it never removes any).
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic emailSendTopic(
            @Value("${kafka.topics.email-send}") String name,
            @Value("${kafka.topics.email-send-partitions:6}") int partitions) {
        return TopicBuilder.name(name).partitions(partitions).build();
    }
}
