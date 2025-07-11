/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *   WSO2 Inc. licenses this file to you under the Apache License,
 *   Version 2.0 (the "License"); you may not use this file except
 *   in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.wso2.carbon.inbound.kafka;

import org.apache.avro.generic.GenericData;
import org.apache.axiom.om.OMElement;
import org.apache.axis2.builder.Builder;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.builder.SOAPBuilder;
import org.apache.axis2.transport.TransportUtils;
import org.apache.commons.io.input.AutoCloseInputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.SynapseException;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.base.SequenceMediator;
import org.wso2.carbon.inbound.endpoint.protocol.generic.GenericPollingConsumer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Kafka Polling Consumer.
 *
 * @since 1.0.0.
 */
public class KafkaMessageConsumer extends GenericPollingConsumer {

    private static final Log log = LogFactory.getLog(KafkaMessageConsumer.class);

    private KafkaConsumer<byte[], byte[]> consumer;

    private String bootstrapServersName;
    private String keyDeserializer;
    private String valueDeserializer;
    private String groupId;
    private String pollTimeout;
    private String topic;
    private String contentType;
    private Properties kafkaProperties;
    private boolean isRegexPattern = false;
    private boolean isDisableAutoCommit;
    private int failureRetryCount;
    private int retryCounter = 0;
    private long failureRetryInterval = -1;
    private String kafkaHeaderPrefix = "";

    public KafkaMessageConsumer(Properties properties, String name, SynapseEnvironment synapseEnvironment,
                                long scanInterval, String injectingSeq, String onErrorSeq, boolean coordination,
                                boolean sequential) {

        super(properties, name, synapseEnvironment, scanInterval, injectingSeq, onErrorSeq, coordination, sequential);
        validateMandatoryParameters(properties);
        createKafkaProperties(properties);
        populateOtherProperties(properties);
    }

    /**
     * Subscribe the kafka consumer and consume the record.
     */
    private void consumeKafkaRecords() {
        try {
            ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.of(Long.parseLong(pollTimeout),
                    ChronoUnit.MILLIS));
            commitRecords(records);
        } catch (WakeupException ex) {
            log.error("Error while wakeup the consumer " + consumer);
            consumer.close();
            consumer = null;
        } catch (Exception ex) {
            log.error("Error while consuming the message " + ex);
        }
    }

    /**
     * The offset will manually commit per record if the auto-commit set to false.
     * An error situation, if SET_ROLLBACK_ONLY set to true, the offset will set to
     * the current record so the next polling always the same record.
     *
     * If failure.retry.count set, then the same record will not poll when the
     * count exceeded.
     *
     * @param records ConsumerRecords
     */
    private void commitRecords(ConsumerRecords<byte[], byte[]> records) {
        long recordOffset = 0;
        TopicPartition topicPartition = null;
        ConsumerRecord failedRecord = null;
        for (TopicPartition partition : records.partitions()) {
            topicPartition = partition;
            List<ConsumerRecord<byte[], byte[]>> partitionRecords = records.records(partition);
            for (ConsumerRecord record : partitionRecords) {
                recordOffset = record.offset();
                MessageContext msgCtx = populateMessageContext(record);

                // Fixes - CS0196510
                Object value  = record.value();
                boolean isConsumed = false;
                boolean poisonPillDetected = false;
                if ( value == null ) {
                    if (isPoisonPill(record)) {
                        poisonPillDetected = true;
                        log.warn("A poison pill was detected for Topic: " + record.topic()
                                + ", Partition No: " + record.partition()
                                + ", Offset: " + record.offset()
                                + ". The respective error details will be injected to the `onError` sequence: "
                                + this.onErrorSeq + " of Kafka Inbound Endpoint: " + name);
                        handlePoisonPill(record, msgCtx);
                    } else {
                        log.warn("A null record was passed and skipped for Topic: " + record.topic()
                                + ", Partition No: " + record.partition()
                                + ", Offset: " + record.offset());
                    }
                    isConsumed = true;
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Injecting the kafka message for inbound mediation. Topic: " + record.topic()
                                + ", Partition No: " + record.partition()
                                + ", Offset: " + record.offset()
                                + ", Kafka_Timestamp: " + record.timestamp());
                    }
                    isConsumed = injectMessage(value.toString(), contentType, msgCtx);
                    if (!isConsumed) {
                        if (log.isDebugEnabled()) {
                            log.debug("Kafka message was not successfully consumed by the inbound mediation."
                                    + " Topic: " + record.topic()
                                    + ", Partition No: " + record.partition()
                                    + ", Offset: " + record.offset()
                                    + ", Kafka_Timestamp: " + record.timestamp());
                        }
                    }
                }

                if (isDisableAutoCommit) {
                    if (poisonPillDetected) {
                        log.info("The poison pill at offset " + record.offset()
                                + " is skipped by setting the offset to the next record.");
                        consumer.seek(topicPartition, recordOffset + 1);
                        continue;
                    }
                    // Manually commit if the record is consumed successfully and auto-commit
                    // set to false
                    if (isConsumed) {
                        consumer.commitSync(Collections.singletonMap(topicPartition,
                                new OffsetAndMetadata(recordOffset + 1)));
                    // if SET_ROLLBACK_ONLY property set to true isConsumed will be false hence
                    // setting the offset to the current record. It ends up in a loop when an error
                    // scenario. For example, if a message always ends up hitting the fault sequence.
                    } else {
                        failedRecord = record;
                        if (failureRetryInterval > 0 && (retryCounter < failureRetryCount || failureRetryCount < 0)) {
                            try {
                                if (log.isDebugEnabled()) {
                                    log.debug("Failed Kafka message will be retried for "
                                            + "inbound mediation after max(poll_interval, failure_retry_interval): "
                                            + Long.max(failureRetryInterval, scanInterval) + "ms.");
                                }
                                Thread.sleep(this.failureRetryInterval);
                            } catch (InterruptedException e) {
                                log.error("The interval for retrying failures was interrupted while waiting.");
                            }
                        }
                        if (failureRetryCount > 0) {
                            retryCounter++;
                        }
                        if (retryCounter < failureRetryCount || failureRetryCount < 0) {
                            consumer.seek(topicPartition, recordOffset);
                        }
                        break;
                    }
                }
            }
        }
        // Check failure retry count exceeded. If yes, set the offset to next record.
        if (retryCounter == failureRetryCount && isDisableAutoCommit) {
            log.warn("The offset set to the next record since failure retry count exceeded.");
            consumer.seek(topicPartition, recordOffset + 1);
            retryCounter = 0;
            if (failedRecord != null) {
                MessageContext msgCtx = populateMessageContext(failedRecord);
                injectErrorMessage("Failed to successfully mediate the message to/in the sequence: "
                        + this.injectingSeq + " of Kafka Inbound Endpoint: " + name + ", even after "
                        + failureRetryCount + " retires.", KafkaConstants.RETRY_EXHAUSTED, msgCtx);
            }
        }
    }

    /**
     * A poison pill (in the context of Kafka) is a record that has been produced to a Kafka topic
     * and always fails when consumed, no matter how many times it is attempted. At this level, a record
     * is considered as a poison pill if the record headers contain either 'KafkaInboundKeyDeserializerException' or
     * 'KafkaInboundValueDeserializerException' header in the record headers.
     *
     * @param record the consumer record
     * @return true if the consumer record's headers contain either 'KafkaInboundKeyDeserializerException' or
     * 'KafkaInboundValueDeserializerException' header.
     */
    private boolean isPoisonPill(ConsumerRecord record) {
        return record.headers().lastHeader(KafkaConstants.KEY_DESERIALIZER_EXCEPTION_HEADER) != null
                || record.headers().lastHeader(KafkaConstants.VALUE_DESERIALIZER_EXCEPTION_HEADER) != null;

    }

    /**
     * Extract the exception details from the deserialization exception headers and inject
     * those details to error sequence.
     *
     * @param record ConsumerRecord instance
     * @param msgCtx Synapse message context
     */
    private void handlePoisonPill(ConsumerRecord record, MessageContext msgCtx) {
        Header keyDeserializationExceptionHeader = record.headers()
                .lastHeader(KafkaConstants.KEY_DESERIALIZER_EXCEPTION_HEADER);
        Header valueDeserializationExceptionHeader = record.headers()
                .lastHeader(KafkaConstants.VALUE_DESERIALIZER_EXCEPTION_HEADER);
        byte [] value;
        if (keyDeserializationExceptionHeader != null) {
            value = keyDeserializationExceptionHeader.value();
        } else {
            value = valueDeserializationExceptionHeader.value();
        }

        try (ObjectInputStream objectInputStream = new ObjectInputStream(new ByteArrayInputStream(value))) {
            // Read the KafkaException from the ObjectInputStream
            KafkaException error = (KafkaException) objectInputStream.readObject();
            injectErrorMessage(error.getMessage(), KafkaConstants.POISON_PILL_DETECTED, msgCtx);

        } catch (ClassNotFoundException e) {
            log.error("Could not deserialize the poison pill message into a 'KafkaException'. Hence, failed to"
                    + " inject the poison pill error details to 'onError' sequence: " + this.onErrorSeq
                    + " of Kafka Inbound Endpoint: " + name, e);
        } catch (IOException e) {
            log.error("Could not deserialize the KafkaException while handling the poison pill. Hence, failed to"
                    + " inject the poison pill error details to 'onError' sequence: " + this.onErrorSeq
                    + " of Kafka Inbound Endpoint: " + name, e);
        }
    }

    /**
     * Populate properties that are related to the functionality of Kafka Inbound Endpoint feature.
     *
     * @param properties properties configured in the Kafka Inbound Endpoint
     */
    private void populateOtherProperties(Properties properties) {
        checkDisableAutoCommit(properties);
        if (properties.getProperty(KafkaConstants.KAFKA_HEADER_PREFIX) != null) {
            kafkaHeaderPrefix = properties.getProperty(KafkaConstants.KAFKA_HEADER_PREFIX).trim();
        }
    }

    /**
     * Check property enable.auto.commit=false and initialize
     * other variable max retry and action after max retry
     */
    private void checkDisableAutoCommit(Properties properties) {
        if ("false".equals(kafkaProperties.getProperty(KafkaConstants.ENABLE_AUTO_COMMIT))) {
            isDisableAutoCommit = true;
            if (properties.getProperty(KafkaConstants.FAILURE_RETRY_COUNT) != null) {
                try {
                    failureRetryCount = Integer.parseInt(properties.getProperty(KafkaConstants.FAILURE_RETRY_COUNT));
                } catch (NumberFormatException e) {
                    log.error("Invalid input for '" + KafkaConstants.FAILURE_RETRY_COUNT
                            + "'. The value should be a valid Integer");
                }
            } else {
                failureRetryCount = Integer.parseInt(KafkaConstants.FAILURE_RETRY_COUNT_DEFAULT);
            }

            if (properties.getProperty(KafkaConstants.FAILURE_RETRY_INTERVAL) != null) {
                try {
                    failureRetryInterval = Integer.parseInt(properties.getProperty(KafkaConstants.FAILURE_RETRY_INTERVAL));
                } catch (NumberFormatException e) {
                    log.error("Invalid input for '" + KafkaConstants.FAILURE_RETRY_INTERVAL
                            + "'. The value should be a positive Integer");
                }
            }
        }
    }

    /**
     * Set the Kafka Records to a MessageContext
     *
     * @param record A Kafka record
     * @return MessageContext A message context with the record header values
     */
    private MessageContext populateMessageContext(ConsumerRecord record) {
        MessageContext msgCtx = createMessageContext();
        msgCtx.setProperty(KafkaConstants.KAFKA_PARTITION_NO, record.partition());
        msgCtx.setProperty(KafkaConstants.KAFKA_MESSAGE_VALUE, record.value());
        msgCtx.setProperty(KafkaConstants.KAFKA_OFFSET, record.offset());
        //noinspection deprecation
        // record.checksum() is deprecated, hence removed.
        msgCtx.setProperty(KafkaConstants.KAFKA_TIMESTAMP, record.timestamp());
        msgCtx.setProperty(KafkaConstants.KAFKA_TIMESTAMP_TYPE, record.timestampType());
        msgCtx.setProperty(KafkaConstants.KAFKA_TOPIC, record.topic());
        msgCtx.setProperty(KafkaConstants.KAFKA_KEY, record.key());
        if (record.value() instanceof GenericData.Record) {
            GenericData.Record val = (GenericData.Record) record.value();
            msgCtx.setProperty(KafkaConstants.KAFKA_SCHEMA_NAME, val.getSchema().getFullName());
        }
        msgCtx.setProperty(KafkaConstants.KAFKA_INBOUND_ENDPOINT_NAME, name);
        msgCtx.setProperty(SynapseConstants.IS_INBOUND, true);
        // Set the kafka headers to the message context
        setDynamicParameters(msgCtx, record.headers());
        return msgCtx;
    }

    /**
     * This will set the dynamic parameters to message context parameter from the kafka headers
     *
     * @param messageContext The message contest
     * @param headers        The headers of the kafka records
     */
    private void setDynamicParameters(MessageContext messageContext, Headers headers) {
        for (Header header : headers) {
            try {
                String headerVal = new String(header.value(), "UTF-8");
                messageContext.setProperty(kafkaHeaderPrefix + header.key(), headerVal);
            } catch (UnsupportedEncodingException e) {
                log.error("Error while getting the kafka header value", e);
            }
        }
    }

    private boolean injectMessage(String strMessage, String contentType, MessageContext msgCtx) {

        AutoCloseInputStream in = new AutoCloseInputStream(new ByteArrayInputStream(strMessage.getBytes()));
        return this.injectMessage(in, contentType, msgCtx);
    }

    private boolean injectMessage(InputStream in, String contentType, MessageContext msgCtx) {
        boolean isConsumed = true;
        try {
            if (log.isDebugEnabled()) {
                log.debug("Processed Custom inbound EP Message of Content-type : " + contentType + " for " + name);
            }

            org.apache.axis2.context.MessageContext axis2MsgCtx = ((Axis2MessageContext) msgCtx)
                    .getAxis2MessageContext();
            Object builder;
            if (StringUtils.isEmpty(contentType)) {
                log.warn("Unable to determine content type for message, setting to application/json for " + name);
            }
            int index = contentType.indexOf(';');
            String type = index > 0 ? contentType.substring(0, index) : contentType;
            builder = BuilderUtil.getBuilderFromSelector(type, axis2MsgCtx);
            if (builder == null) {
                if (log.isDebugEnabled()) {
                    log.debug("No message builder found for type '" + type + "'. Falling back to SOAP. for" + name);
                }
                builder = new SOAPBuilder();
            }

            OMElement documentElement1 = ((Builder) builder).processDocument(in, contentType, axis2MsgCtx);
            msgCtx.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement1));
            if (this.injectingSeq == null || "".equals(this.injectingSeq)) {
                log.error("Sequence name not specified. Sequence : " + this.injectingSeq + " for " + name);
                isConsumed = false;
            }

            SequenceMediator seq = (SequenceMediator) this.synapseEnvironment.getSynapseConfiguration().getSequence(
                    this.injectingSeq);
            if (seq == null) {
                throw new SynapseException(
                        "Sequence with name : " + this.injectingSeq + " is not found to mediate the message.");
            }
            seq.setErrorHandler(this.onErrorSeq);
            if (log.isDebugEnabled()) {
                log.debug("injecting message to sequence : " + this.injectingSeq + " of " + name);
            }
            if (!this.synapseEnvironment.injectInbound(msgCtx, seq, this.sequential)) {
                isConsumed = false;
            }
            if (isRollback(msgCtx)) {
                isConsumed = false;
            }
        } catch (Exception e) {
            log.error("Error while processing the Kafka inbound endpoint Message and the message should be in the "
                    + "format of " + contentType, e);
            isConsumed = false;
        }
        return isConsumed;
    }

    /**
     * Inject error message details as MessageContext properties to the 'onError' sequence that is configured
     * in the Kafka Inbound Endpoint. The SET_ROLLBACK_ONLY property is ignored here as the fault sequence
     * is purposefully and forcefully invoked.
     *
     * @param message error message to be set in the 'ERROR_MESSAGE' MessageContext property.
     * @param msgCtx Synapse message context.
     * @return false if the 'onError' sequence name is not configured in the Kafka Inbound Endpoint or
     * if a sequence for the configured name is not found or if an error occurred while invoking the
     * handlers in the Synapse Environment prior to mediate to the sequence. Otherwise, return true.
     */
    private boolean injectErrorMessage(String message, String errorCode, MessageContext msgCtx) {

        if (StringUtils.isEmpty(this.onErrorSeq)) {
            log.error("Could not mediate the error message as the 'onError' sequence name not specified for Kafka "
                    + "Inbound Endpoint: " + name + ". Hence, no retry attempted on failure.");
            return false;
        }

        SequenceMediator errorSeq = (SequenceMediator) this.synapseEnvironment.getSynapseConfiguration().getSequence(
                this.onErrorSeq);
        if (errorSeq == null) {
            log.error("Could not mediate the error message as the 'onError' Sequence with name: " + this.onErrorSeq
                    + " not found. Hence, no retry attempted on failure.");
            return false;
        }

        // Populate error details to the message context as properties
        msgCtx.setProperty(SynapseConstants.ERROR_CODE, errorCode);
        msgCtx.setProperty(SynapseConstants.ERROR_MESSAGE, message);

        if (log.isDebugEnabled()) {
            log.debug("injecting message to 'onError' sequence: " + this.onErrorSeq
                    + " of Kafka Inbound Endpoint: " + name);
        }
        if (!this.synapseEnvironment.injectInbound(msgCtx, errorSeq, this.sequential)) {
            log.warn("Could not inject the error details to 'onError' sequence: " + this.onErrorSeq
                    + " of Kafka Inbound Endpoint: " + name);
            return false;
        }

        return true;
    }

    /**
     * Check the SET_ROLLBACK_ONLY property set to true
     *
     * @param msgCtx SynapseMessageContext
     * @return true or false
     */
    private boolean isRollback(MessageContext msgCtx) {
        // check rollback property from synapse context
        Object rollbackProp = msgCtx.getProperty(KafkaConstants.SET_ROLLBACK_ONLY);
        if (rollbackProp == null) {
            // check rollback property from operation context in axis2 message context
            org.apache.axis2.context.MessageContext axis2MessageCtx =
                    ((Axis2MessageContext) msgCtx).getAxis2MessageContext();
            rollbackProp = axis2MessageCtx.getOperationContext().getProperty(KafkaConstants.SET_ROLLBACK_ONLY);
        }

        if (rollbackProp != null) {
            return (rollbackProp instanceof Boolean && ((Boolean) rollbackProp))
                    || (rollbackProp instanceof String && Boolean.valueOf((String) rollbackProp));
        }
        return false;
    }

    /**
     * load essential property for Kafka inbound endpoint.
     *
     * @param properties The mandatory parameters of Kafka.
     */
    private void validateMandatoryParameters(Properties properties) {

        if (log.isDebugEnabled()) {
            log.debug("Starting to load the Kafka Mandatory parameters");
        }

        bootstrapServersName = properties.getProperty(KafkaConstants.BOOTSTRAP_SERVERS_NAME);
        keyDeserializer = properties.getProperty(KafkaConstants.KEY_DESERIALIZER);
        if (KafkaConstants.DEFAULT_ERROR_HANDLING_DESERIALIZER_CLASS.equals(keyDeserializer)) {
            if (StringUtils.isEmpty(properties.getProperty(KafkaConstants.KEY_DELEGATE_DESERIALIZER))) {
                throw new SynapseException("The 'key.delegate.deserializer' property must not be empty when "
                        + "'key.deserializer' is configured with the default error handling deserializer.");
            }
        }
        valueDeserializer = properties.getProperty(KafkaConstants.VALUE_DESERIALIZER);
        if (KafkaConstants.DEFAULT_ERROR_HANDLING_DESERIALIZER_CLASS.equals(valueDeserializer)) {
            if (StringUtils.isEmpty(properties.getProperty(KafkaConstants.VALUE_DELEGATE_DESERIALIZER))) {
                throw new SynapseException("The 'value.delegate.deserializer' property must not be empty when "
                        + "'value.deserializer' is configured with the default error handling deserializer.");
            }
        }
        groupId = properties.getProperty(KafkaConstants.GROUP_ID);
        pollTimeout = properties.getProperty(KafkaConstants.POLL_TIMEOUT);
        // check whether the properties have topic name or topic pattern
        if (properties.getProperty(KafkaConstants.TOPIC_NAME) != null) {
            isRegexPattern = false;
            topic = properties.getProperty(KafkaConstants.TOPIC_NAME);
        } else if (properties.getProperty(KafkaConstants.TOPIC_PATTERN) != null) {
            isRegexPattern = true;
            topic = properties.getProperty(KafkaConstants.TOPIC_PATTERN);
        }
        contentType = properties.getProperty(KafkaConstants.CONTENT_TYPE);

        if (StringUtils.isEmpty(bootstrapServersName) || StringUtils.isEmpty(keyDeserializer) || StringUtils
                .isEmpty(valueDeserializer) || StringUtils.isEmpty(groupId) || StringUtils.isEmpty(pollTimeout)
                || StringUtils.isEmpty(topic) || StringUtils.isEmpty(contentType)) {
            throw new SynapseException(
                    "Mandatory Parameters cannot be Empty, The mandatory parameters are bootstrap.servers, "
                            + "key.deserializer, value.deserializer, group.id, poll.timeout, "
                            + "(topic.name or topic.pattern) and contentType");
        }
    }

    /**
     * Create the message context.
     */
    private MessageContext createMessageContext() {

        MessageContext msgCtx = this.synapseEnvironment.createMessageContext();
        org.apache.axis2.context.MessageContext axis2MsgCtx = ((Axis2MessageContext) msgCtx).getAxis2MessageContext();
        axis2MsgCtx.setServerSide(true);
        axis2MsgCtx.setMessageID(String.valueOf(UUID.randomUUID()));
        return msgCtx;
    }

    @Override
    public Object poll() {

        if (consumer == null) {
            try {
                consumer = new KafkaConsumer<>(kafkaProperties);
                if (!isRegexPattern) {
                    if (properties.getProperty(KafkaConstants.TOPIC_PARTITIONS) != null) {
                        String[] partitionsArray = properties.getProperty(KafkaConstants.TOPIC_PARTITIONS)
                                .split(",");
                        List<TopicPartition> topicPartitions = new ArrayList<>();
                        for (String partition : partitionsArray) {
                            topicPartitions.add(new TopicPartition(topic, Integer.parseInt(partition)));
                        }
                        consumer.assign(topicPartitions);
                    } else {
                        consumer.subscribe(Collections.singletonList(topic));
                    }
                } else {
                    Pattern r = Pattern.compile(topic);
                    consumer.subscribe(r);
                }
            } catch (Exception e) {
                throw new SynapseException("Failed to construct kafka consumer", e);
            }
        }
        consumeKafkaRecords();
        return null;
    }

    /**
     * Close the connection to the Kafka.
     */
    public void destroy() {

        try {
            if (consumer != null) {
                consumer.wakeup();
                if (log.isDebugEnabled()) {
                    log.debug("The Kafka consumer has been close ! for " + name);
                }
            }
        } catch (Exception e) {
            log.error("Error while shutdown the Kafka consumer " + name + " " + e.getMessage(), e);
        }
    }

    /**
     * Create kafka properties.
     *
     * @param properties The kafka properties.
     */
    private void createKafkaProperties(Properties properties) {

        kafkaProperties = new Properties();
        kafkaProperties.put(KafkaConstants.BOOTSTRAP_SERVERS_NAME, bootstrapServersName);
        kafkaProperties.put(KafkaConstants.KEY_DESERIALIZER, keyDeserializer);
        kafkaProperties.put(KafkaConstants.VALUE_DESERIALIZER, valueDeserializer);
        kafkaProperties.put(KafkaConstants.GROUP_ID, groupId);
        kafkaProperties.put(KafkaConstants.POLL_TIMEOUT, pollTimeout);

        kafkaProperties.put(KafkaConstants.ENABLE_AUTO_COMMIT, properties
                .getProperty(KafkaConstants.ENABLE_AUTO_COMMIT, KafkaConstants.ENABLE_AUTO_COMMIT_DEFAULT));

        kafkaProperties.put(KafkaConstants.AUTO_COMMIT_INTERVAL_MS, properties
                .getProperty(KafkaConstants.AUTO_COMMIT_INTERVAL_MS,
                        KafkaConstants.AUTO_COMMIT_INTERVAL_MS_DEFAULT));

        kafkaProperties.put(KafkaConstants.SESSION_TIMEOUT_MS, properties
                .getProperty(KafkaConstants.SESSION_TIMEOUT_MS, KafkaConstants.SESSION_TIMEOUT_MS_DEFAULT));

        kafkaProperties.put(KafkaConstants.FETCH_MIN_BYTES,
                properties.getProperty(KafkaConstants.FETCH_MIN_BYTES, KafkaConstants.FETCH_MIN_BYTES_DEFAULT));

        kafkaProperties.put(KafkaConstants.HEARTBEAT_INTERVAL_MS, properties
                .getProperty(KafkaConstants.HEARTBEAT_INTERVAL_MS, KafkaConstants.HEARTBEAT_INTERVAL_MS_DEFAULT));

        kafkaProperties.put(KafkaConstants.MAX_PARTITION_FETCH_BYTES, properties
                .getProperty(KafkaConstants.MAX_PARTITION_FETCH_BYTES,
                        KafkaConstants.MAX_PARTITION_FETCH_BYTES_DEFAULT));

        if (properties.getProperty(KafkaConstants.KEY_DELEGATE_DESERIALIZER) != null) {
            kafkaProperties.put(KafkaConstants.KEY_DELEGATE_DESERIALIZER, properties
                    .getProperty(KafkaConstants.KEY_DELEGATE_DESERIALIZER));
        }

        if (properties.getProperty(KafkaConstants.VALUE_DELEGATE_DESERIALIZER) != null) {
            kafkaProperties.put(KafkaConstants.VALUE_DELEGATE_DESERIALIZER, properties
                    .getProperty(KafkaConstants.VALUE_DELEGATE_DESERIALIZER));
        }

        if (properties.getProperty(KafkaConstants.VALUE_DESERIALIZER)
                .equalsIgnoreCase(KafkaConstants.KAFKA_AVRO_DESERIALIZER)
                || KafkaConstants.KAFKA_AVRO_DESERIALIZER
                .equalsIgnoreCase(properties.getProperty(KafkaConstants.VALUE_DELEGATE_DESERIALIZER))){
            kafkaProperties.put(KafkaConstants.SCHEMA_REGISTRY_URL, properties.
                    getProperty(KafkaConstants.SCHEMA_REGISTRY_URL, KafkaConstants.DEFAULT_SCHEMA_REGISTRY_URL));

            if (properties.getProperty(KafkaConstants.SCHEMA_REGISTRY_BASIC_AUTH_CREDENTIALS_SOURCE) != null) {
                kafkaProperties.put(KafkaAvroDeserializerConfig.BASIC_AUTH_CREDENTIALS_SOURCE,
                        properties.getProperty(KafkaConstants.SCHEMA_REGISTRY_BASIC_AUTH_CREDENTIALS_SOURCE));
            }

            if (properties.getProperty(KafkaConstants.SCHEMA_REGISTRY_BASIC_AUTH_USER_INFO) != null) {
                kafkaProperties.put(KafkaAvroDeserializerConfig.USER_INFO_CONFIG,
                        properties.getProperty(KafkaConstants.SCHEMA_REGISTRY_BASIC_AUTH_USER_INFO));
            }
        }

        if (properties.getProperty(KafkaConstants.SSL_KEY_PASSWORD) != null) {
            kafkaProperties
                    .put(KafkaConstants.SSL_KEY_PASSWORD, properties.getProperty(KafkaConstants.SSL_KEY_PASSWORD));
        }

        if (properties.getProperty(KafkaConstants.SSL_KEYSTORE_LOCATION) != null) {
            kafkaProperties.put(KafkaConstants.SSL_KEYSTORE_LOCATION,
                    properties.getProperty(KafkaConstants.SSL_KEYSTORE_LOCATION));
        }

        if (properties.getProperty(KafkaConstants.SSL_KEYSTORE_PASSWORD) != null) {
            kafkaProperties.put(KafkaConstants.SSL_KEYSTORE_PASSWORD,
                    properties.getProperty(KafkaConstants.SSL_KEYSTORE_PASSWORD));
        }

        if (properties.getProperty(KafkaConstants.SSL_TRUSTSTORE_LOCATION) != null) {
            kafkaProperties.put(KafkaConstants.SSL_TRUSTSTORE_LOCATION,
                    properties.getProperty(KafkaConstants.SSL_TRUSTSTORE_LOCATION));
        }

        if (properties.getProperty(KafkaConstants.SSL_TRUSTSTORE_PASSWORD) != null) {
            kafkaProperties.put(KafkaConstants.SSL_TRUSTSTORE_PASSWORD,
                    properties.getProperty(KafkaConstants.SSL_TRUSTSTORE_PASSWORD));
        }

        kafkaProperties.put(KafkaConstants.AUTO_OFFSET_RESET,
                properties.getProperty(KafkaConstants.AUTO_OFFSET_RESET, KafkaConstants.AUTO_OFFSET_RESET_DEFAULT));

        kafkaProperties.put(KafkaConstants.CONNECTIONS_MAX_IDLE_MS, properties
                .getProperty(KafkaConstants.CONNECTIONS_MAX_IDLE_MS,
                        KafkaConstants.CONNECTIONS_MAX_IDLE_MS_DEFAULT));

        kafkaProperties.put(KafkaConstants.EXCLUDE_INTERNAL_TOPICS, properties
                .getProperty(KafkaConstants.EXCLUDE_INTERNAL_TOPICS,
                        KafkaConstants.EXCLUDE_INTERNAL_TOPICS_DEFAULT));

        kafkaProperties.put(KafkaConstants.FETCH_MAX_BYTES,
                properties.getProperty(KafkaConstants.FETCH_MAX_BYTES, KafkaConstants.FETCH_MAX_BYTES_DEFAULT));

        kafkaProperties.put(KafkaConstants.MAX_POLL_INTERVAL_MS, properties
                .getProperty(KafkaConstants.MAX_POLL_INTERVAL_MS, KafkaConstants.MAX_POLL_INTERVAL_MS_DEFAULT));

        kafkaProperties.put(KafkaConstants.MAX_POLL_RECORDS,
                properties.getProperty(KafkaConstants.MAX_POLL_RECORDS, KafkaConstants.MAX_POLL_RECORDS_DEFAULT));

        kafkaProperties.put(KafkaConstants.PARTITION_ASSIGNMENT_STRATEGY, properties
                .getProperty(KafkaConstants.PARTITION_ASSIGNMENT_STRATEGY,
                        KafkaConstants.PARTITION_ASSIGNMENT_STRATEGY_DEFAULT));

        kafkaProperties.put(KafkaConstants.RECEIVER_BUFFER_BYTES, properties
                .getProperty(KafkaConstants.RECEIVER_BUFFER_BYTES, KafkaConstants.RECEIVER_BUFFER_BYTES_DEFAULT));

        kafkaProperties.put(KafkaConstants.REQUEST_TIMEOUT_MS, properties
                .getProperty(KafkaConstants.REQUEST_TIMEOUT_MS, KafkaConstants.REQUEST_TIMEOUT_MS_DEFAULT));

        if (properties.getProperty(KafkaConstants.SASL_JAAS_CONFIG) != null) {
            kafkaProperties
                    .put(KafkaConstants.SASL_JAAS_CONFIG, properties.getProperty(KafkaConstants.SASL_JAAS_CONFIG));
        }

        if (properties.getProperty(KafkaConstants.SASL_CLIENT_CALLBACK_HANDLER_CLASS) != null) {
            kafkaProperties.put(KafkaConstants.SASL_CLIENT_CALLBACK_HANDLER_CLASS,
                    properties.getProperty(KafkaConstants.SASL_CLIENT_CALLBACK_HANDLER_CLASS));
        }

        if (properties.getProperty(KafkaConstants.SASL_LOGIN_CLASS) != null) {
            kafkaProperties.put(KafkaConstants.SASL_LOGIN_CLASS, properties.getProperty(KafkaConstants.SASL_LOGIN_CLASS));
        }

        if (properties.getProperty(KafkaConstants.SASL_KERBEROS_SERVICE_NAME) != null) {
            kafkaProperties.put(KafkaConstants.SASL_KERBEROS_SERVICE_NAME,
                    properties.getProperty(KafkaConstants.SASL_KERBEROS_SERVICE_NAME));
        }

        if (properties.getProperty(KafkaConstants.SASL_MECANISM) != null) {
            kafkaProperties.put(KafkaConstants.SASL_MECANISM, properties.getProperty(KafkaConstants.SASL_MECANISM));
        }

        if (properties.getProperty(KafkaConstants.SECURITY_PROTOCOL) != null) {
            kafkaProperties.put(KafkaConstants.SECURITY_PROTOCOL,
                    properties.getProperty(KafkaConstants.SECURITY_PROTOCOL));
        }

        kafkaProperties.put(KafkaConstants.SEND_BUFFER_BYTES,
                properties.getProperty(KafkaConstants.SEND_BUFFER_BYTES, KafkaConstants.SEND_BUFFER_BYTES_DEFAULT));

        if (properties.getProperty(KafkaConstants.SSL_ENABLED_PROTOCOL) != null) {
            String[] sslEnabledProtocolsArray = properties.getProperty(KafkaConstants.SSL_ENABLED_PROTOCOL)
                    .split(",");
            kafkaProperties.put(KafkaConstants.SSL_ENABLED_PROTOCOL, Arrays.asList(sslEnabledProtocolsArray));
        }

        if (properties.getProperty(KafkaConstants.SSL_KEYSTORE_TYPE) != null) {
            kafkaProperties.put(KafkaConstants.SSL_KEYSTORE_TYPE,
                    properties.getProperty(KafkaConstants.SSL_KEYSTORE_TYPE));
        }

        if (properties.getProperty(KafkaConstants.SSL_PROTOCOL) != null) {
            kafkaProperties.put(KafkaConstants.SSL_PROTOCOL, properties.getProperty(KafkaConstants.SSL_PROTOCOL));
        }

        if (properties.getProperty(KafkaConstants.SSL_PROVIDER) != null) {
            kafkaProperties.put(KafkaConstants.SSL_PROVIDER, properties.getProperty(KafkaConstants.SSL_PROVIDER));
        }

        if (properties.getProperty(KafkaConstants.SSL_TRUSTSTORE_TYPE) != null) {
            kafkaProperties.put(KafkaConstants.SSL_TRUSTSTORE_TYPE,
                    properties.getProperty(KafkaConstants.SSL_TRUSTSTORE_TYPE));
        }

        kafkaProperties.put(KafkaConstants.CHECK_CRCS,
                properties.getProperty(KafkaConstants.CHECK_CRCS, KafkaConstants.CHECK_CRCS_DEFAULT));

        kafkaProperties.put(KafkaConstants.CLIENT_ID,
                properties.getProperty(KafkaConstants.CLIENT_ID, KafkaConstants.CLIENT_ID_DEFAULT));

        kafkaProperties.put(KafkaConstants.FETCH_MAX_WAIT_MS,
                properties.getProperty(KafkaConstants.FETCH_MAX_WAIT_MS, KafkaConstants.FETCH_MAX_WAIT_MS_DEFAULT));

        kafkaProperties.put(KafkaConstants.INTERCEPTOR_CLASSES, properties
                .getProperty(KafkaConstants.INTERCEPTOR_CLASSES, KafkaConstants.INTERCEPTOR_CLASSES_DEFAULT));

        kafkaProperties.put(KafkaConstants.METADATA_MAX_AGE_MS, properties
                .getProperty(KafkaConstants.METADATA_MAX_AGE_MS, KafkaConstants.METADATA_MAX_AGE_MS_DEFAULT));

        kafkaProperties.put(KafkaConstants.METRIC_REPORTERS,
                properties.getProperty(KafkaConstants.METRIC_REPORTERS, KafkaConstants.METRIC_REPORTERS_DEFAULT));

        kafkaProperties.put(KafkaConstants.METRICS_NUM_SAMPLES, properties
                .getProperty(KafkaConstants.METRICS_NUM_SAMPLES, KafkaConstants.METRICS_NUM_SAMPLES_DEFAULT));

        kafkaProperties.put(KafkaConstants.METRICS_RECORDING_LEVEL, properties
                .getProperty(KafkaConstants.METRICS_RECORDING_LEVEL,
                        KafkaConstants.METRICS_RECORDING_LEVEL_DEFAULT));

        kafkaProperties.put(KafkaConstants.METRICS_SAMPLE_WINDOW_MS, properties
                .getProperty(KafkaConstants.METRICS_SAMPLE_WINDOW_MS,
                        KafkaConstants.METRICS_SAMPLE_WINDOW_MS_DEFAULT));

        kafkaProperties.put(KafkaConstants.RECONNECT_BACKOFF_MS, properties
                .getProperty(KafkaConstants.RECONNECT_BACKOFF_MS, KafkaConstants.RECONNECT_BACKOFF_MS_DEFAULT));

        kafkaProperties.put(KafkaConstants.RETRY_BACKOFF_MS,
                properties.getProperty(KafkaConstants.RETRY_BACKOFF_MS, KafkaConstants.RETRY_BACKOFF_MS_DEFAULT));

        kafkaProperties.put(KafkaConstants.AVRO_USE_LOGICAL_TYPE_CONVERTERS, properties
                .getProperty(KafkaConstants.AVRO_USE_LOGICAL_TYPE_CONVERTERS, KafkaConstants.AVRO_USE_LOGICAL_TYPE_CONVERTERS_DEFAULT));

        if (properties.getProperty(KafkaConstants.SASL_KERBEROS_KINIT_CMD) != null) {
            kafkaProperties.put(KafkaConstants.SASL_KERBEROS_KINIT_CMD,
                    properties.getProperty(KafkaConstants.SASL_KERBEROS_KINIT_CMD));
        }

        if (properties.getProperty(KafkaConstants.SASL_KERBEROS_MIN_TIME_BEFORE_RELOGIN) != null) {
            kafkaProperties.put(KafkaConstants.SASL_KERBEROS_MIN_TIME_BEFORE_RELOGIN,
                    properties.getProperty(KafkaConstants.SASL_KERBEROS_MIN_TIME_BEFORE_RELOGIN));
        }

        if (properties.getProperty(KafkaConstants.SASL_KERBEROS_TICKET_RENEW_JITTER) != null) {
            properties.setProperty(KafkaConstants.SASL_KERBEROS_TICKET_RENEW_JITTER,
                    properties.getProperty(KafkaConstants.SASL_KERBEROS_TICKET_RENEW_JITTER));
        }

        if (properties.getProperty(KafkaConstants.SASL_KERBEROS_TICKET_RENEW_WINDOW_FACTOR) != null) {
            kafkaProperties.put(KafkaConstants.SASL_KERBEROS_TICKET_RENEW_WINDOW_FACTOR,
                    properties.getProperty(KafkaConstants.SASL_KERBEROS_TICKET_RENEW_WINDOW_FACTOR));
        }

        if (properties.getProperty(KafkaConstants.SSL_CIPHER_SUITES) != null) {
            properties.setProperty(KafkaConstants.SSL_CIPHER_SUITES,
                    properties.getProperty(KafkaConstants.SSL_CIPHER_SUITES));
        }

        if (properties.getProperty(KafkaConstants.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM) != null) {
            kafkaProperties.put(KafkaConstants.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM,
                    properties.getProperty(KafkaConstants.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM));
        }

        if (properties.getProperty(KafkaConstants.SSL_CIPHER_SUITES) != null) {
            kafkaProperties.put(KafkaConstants.SSL_CIPHER_SUITES,
                    properties.getProperty(KafkaConstants.SSL_CIPHER_SUITES));
        }

        if (properties.getProperty(KafkaConstants.SSL_KEYMANAGER_ALGORITHM) != null) {
            kafkaProperties.put(KafkaConstants.SSL_KEYMANAGER_ALGORITHM,
                    properties.getProperty(KafkaConstants.SSL_KEYMANAGER_ALGORITHM));
        }

        if (properties.getProperty(KafkaConstants.SSL_SECURE_RANDOM_IMPLEMENTATION) != null) {
            kafkaProperties.put(KafkaConstants.SSL_SECURE_RANDOM_IMPLEMENTATION,
                    properties.getProperty(KafkaConstants.SSL_SECURE_RANDOM_IMPLEMENTATION));
        }

        if (properties.getProperty(KafkaConstants.SSL_TRUSTMANAGER_ALGORITHM) != null) {
            kafkaProperties.put(KafkaConstants.SSL_TRUSTMANAGER_ALGORITHM,
                    properties.getProperty(KafkaConstants.SSL_TRUSTMANAGER_ALGORITHM));
        }

        if (properties.getProperty(KafkaConstants.SASL_OAUTHBEARER_TOKEN_ENDPOINT) != null) {
            kafkaProperties.put(KafkaConstants.SASL_OAUTHBEARER_TOKEN_ENDPOINT,
                    properties.getProperty(KafkaConstants.SASL_OAUTHBEARER_TOKEN_ENDPOINT));
        }

        if (properties.getProperty(KafkaConstants.SASL_OAUTHBEARER_SCOPE_CLAIM_NAME) != null) {
            kafkaProperties.put(KafkaConstants.SASL_OAUTHBEARER_SCOPE_CLAIM_NAME,
                    properties.getProperty(KafkaConstants.SASL_OAUTHBEARER_SCOPE_CLAIM_NAME));
        }

        if (properties.getProperty(KafkaConstants.KAFKA_LOGIN_CALLBACK_HANDLER_CLASS) != null) {
            kafkaProperties.put(KafkaConstants.KAFKA_LOGIN_CALLBACK_HANDLER_CLASS,
                    properties.getProperty(KafkaConstants.KAFKA_LOGIN_CALLBACK_HANDLER_CLASS));
        }

        if (properties.getProperty(KafkaConstants.SASL_LOGIN_CONNECT_TIMEOUT) != null) {
            kafkaProperties.put(KafkaConstants.SASL_LOGIN_CONNECT_TIMEOUT,
                    properties.getProperty(KafkaConstants.SASL_LOGIN_CONNECT_TIMEOUT));
        }

        if (properties.getProperty(KafkaConstants.SASL_LOGIN_READ_TIMEOUT) != null) {
            kafkaProperties.put(KafkaConstants.SASL_LOGIN_READ_TIMEOUT,
                    properties.getProperty(KafkaConstants.SASL_LOGIN_READ_TIMEOUT));
        }

        if (properties.getProperty(KafkaConstants.SASL_LOGIN_RETRY_BACKOFF) != null) {
            kafkaProperties.put(KafkaConstants.SASL_LOGIN_RETRY_BACKOFF,
                    properties.getProperty(KafkaConstants.SASL_LOGIN_RETRY_BACKOFF));
        }

        if (properties.getProperty(KafkaConstants.SASL_LOGIN_RETRY_BACKOFF_MAX) != null) {
            kafkaProperties.put(KafkaConstants.SASL_LOGIN_RETRY_BACKOFF_MAX,
                    properties.getProperty(KafkaConstants.SASL_LOGIN_RETRY_BACKOFF_MAX));
        }
    }
}
