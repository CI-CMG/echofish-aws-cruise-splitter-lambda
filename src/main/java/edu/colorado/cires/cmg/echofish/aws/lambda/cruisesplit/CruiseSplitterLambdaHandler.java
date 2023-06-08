package edu.colorado.cires.cmg.echofish.aws.lambda.cruisesplit;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.colorado.cires.cmg.echofish.data.dynamo.FileInfoRecord;
import edu.colorado.cires.cmg.echofish.data.model.CruiseProcessingMessage;
import edu.colorado.cires.cmg.echofish.data.model.jackson.ObjectMapperCreator;
import edu.colorado.cires.cmg.echofish.data.s3.S3Operations;
import edu.colorado.cires.cmg.echofish.data.sns.SnsNotifierFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class CruiseSplitterLambdaHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(CruiseSplitterLambdaHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = ObjectMapperCreator.create();


  private final S3Operations s3;
  private final SnsNotifierFactory sns;
  private final AmazonDynamoDB client;
  private final CruiseSplitterLambdaConfiguration configuration;
  private final Supplier<Instant> nowProvider;

  public CruiseSplitterLambdaHandler(S3Operations s3, SnsNotifierFactory sns, AmazonDynamoDB client, CruiseSplitterLambdaConfiguration configuration, Supplier<Instant> nowProvider) {
    this.s3 = s3;
    this.sns = sns;
    this.client = client;
    this.configuration = configuration;
    this.nowProvider = nowProvider;
  }

  public void handleRequest(CruiseProcessingMessage message) {

    LOGGER.info("Started Event: {}", message);

    for (String fileName : getRawFiles(message)) {
      message = copyMessage(message);
      message.setFileName(fileName);
      String fileStatus = getFileStatus(message).orElse("NONE");
      if (!fileStatus.equals("SUCCESS")) {
        setProcessingFileStatus(message);
        notifyTopic(message);
      }
    }

    LOGGER.info("Finished Event: {}", message);

  }

  private static CruiseProcessingMessage copyMessage(CruiseProcessingMessage source) {
    try {
      return OBJECT_MAPPER.readValue(OBJECT_MAPPER.writeValueAsString(source), CruiseProcessingMessage.class);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Unable to serialize message", e);
    }
  }

  private List<String> getRawFiles(CruiseProcessingMessage message) {
    String prefix = String.format("data/raw/%s/%s/%s/", message.getShipName(), message.getCruiseName(), message.getSensorName());
    // Note any files with predicate 'NOISE' are to be ignored, see: "Bell_M._Shimada/SH1507"
    return s3.listObjects(configuration.getInputBucket(), prefix).stream()
            .filter(key -> key.endsWith(".raw") && !key.contains("NOISE"))
            .map(key -> key.split("/")[5])
            .sorted()
            .collect(Collectors.toList());
  }

  private void notifyTopic(CruiseProcessingMessage message) {
    sns.createNotifier().notify(configuration.getTopicArn(), message);
  }

  private Optional<String> getFileStatus(CruiseProcessingMessage message) {
    DynamoDBMapper mapper = new DynamoDBMapper(client);
    FileInfoRecord record = mapper.load(
            FileInfoRecord.class,
            message.getFileName(),
            message.getCruiseName(),
            DynamoDBMapperConfig.TableNameOverride.withTableNameReplacement(configuration.getTableName()).config());
    return Optional.ofNullable(record).map(FileInfoRecord::getPipelineStatus);
  }

  private void setProcessingFileStatus(CruiseProcessingMessage message) {
    DynamoDBMapper mapper = new DynamoDBMapper(client);
    FileInfoRecord record = new FileInfoRecord();
    record.setFileName(message.getFileName());
    record.setCruiseName(message.getCruiseName());
    record.setShipName(message.getShipName());
    record.setSensorName(message.getSensorName());
    record.setPipelineTime(nowProvider.get().toString());
    record.setPipelineStatus("PROCESSING");
    mapper.save(record, DynamoDBMapperConfig.TableNameOverride.withTableNameReplacement(configuration.getTableName()).config());
  }

}
