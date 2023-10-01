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
import java.util.*;
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

    if (message.getCruiseName() == null || message.getCruiseName().isEmpty()) {
      throw new IllegalArgumentException("cruiseName is required");
    }

    if (message.getShipName() == null || message.getShipName().isEmpty()) {
      throw new IllegalArgumentException("shipName is required");
    }

    if (message.getSensorName() == null || message.getSensorName().isEmpty()) {
      throw new IllegalArgumentException("sensorName is required");
    }

    ArrayList<CruiseProcessingMessage> messages = new ArrayList<>();
    for (String fileName : getRawFiles(message)) {
      message = copyMessage(message);
      message.setFileName(fileName);
      String fileStatus = getFileStatus(message).orElse("NONE");
      if (!fileStatus.equals(FileInfoRecord.PipelineStatus.SUCCESS_CRUISE_SPLITTER)) {
        setProcessingFileStatus(message);
        messages.add(message);
      }
    }
    messages.forEach(this::notifyTopic);

    LOGGER.info("Finished Event: {}", message);
  }

  private static CruiseProcessingMessage copyMessage(CruiseProcessingMessage source) {
    try {
      return OBJECT_MAPPER.readValue(OBJECT_MAPPER.writeValueAsString(source), CruiseProcessingMessage.class);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Unable to serialize message", e);
    }
  }

  private static String getTime(String fileName) {
    return fileName.split("-", 2)[1].split("\\.", 2)[0];
  }

  private List<String> getRawFiles(CruiseProcessingMessage message) {
    String prefix = String.format("data/raw/%s/%s/%s/", message.getShipName(), message.getCruiseName(), message.getSensorName());
    // Note any files with predicate 'NOISE' are to be ignored, see: "Bell_M._Shimada/SH1507"
    ArrayList<String> rawNoiseRemoved = new ArrayList<>(s3.listObjects(configuration.getInputBucket(), prefix).stream()
            .filter(key -> key.endsWith(".raw") && !key.contains("NOISE"))
            .map(key -> key.split("/")[5])
            .sorted()
            .collect(Collectors.toList())
    );

//    List<String> rawFilesFiltered = new ArrayList<>(Arrays.asList("A000", "A123", "A124", "A455", "A456", "A789"));
//    if (message.getShipName() == "Miller_Freeman") {
//        list.removeIf(s -> s.compareToIgnoreCase("A123") < 0);
//        list.removeIf(s -> s.compareToIgnoreCase("A456") > 0);
//    }
    // MF0710, Miller_Freeman, HAKE2007-D20070708-T010210.raw to HAKE2007-D20070708-T200449.raw
    if (message.getShipName().equals("Miller_Freeman") && message.getCruiseName().equals("MF0710")) {
        LOGGER.info("Filtering to subset for Miller Freeman MF0710");
        rawNoiseRemoved.removeIf(s -> getTime(s).compareTo("D20070708-T010210") < 0);
        rawNoiseRemoved.removeIf(s -> getTime(s).compareTo("D20070708-T200449") > 0);
    }
    // MF0903, Miller_Freeman, MF2009-D20090724-T015244.raw to MF2009-D20090724-T183253.raw
    if (message.getShipName().equals("Miller_Freeman") && message.getCruiseName().equals("MF0903")) {
      LOGGER.info("Filtering to subset for Miller Freeman MF0903");
      rawNoiseRemoved.removeIf(s -> getTime(s).compareTo("D20090724-T015244") < 0);
      rawNoiseRemoved.removeIf(s -> getTime(s).compareTo("D20090724-T183253") > 0);
    }
    // SH1103, Bell_M._Shimada, HAKE2011-D20110721-T203942.raw to HAKE2011-D20110722-T005319.raw
    if (message.getShipName().equals("Bell_M._Shimada") && message.getCruiseName().equals("SH1103")) {
      LOGGER.info("Filtering to subset for Bell M Shimada SH1103");
      rawNoiseRemoved.removeIf(s -> getTime(s).compareTo("D20110721-T203942") < 0);
      rawNoiseRemoved.removeIf(s -> getTime(s).compareTo("D20110722-T005319") > 0);
    }
    // SH1204, Bell_M._Shimada, SaKe_2012-D20120724-T192810.raw to SaKe_2012-D20120725-T004607.raw
    if (message.getShipName().equals("Bell_M._Shimada") && message.getCruiseName().equals("SH1204")) {
      LOGGER.info("Filtering to subset for Bell M Shimada SH1204");
      rawNoiseRemoved.removeIf(s -> getTime(s).compareTo("D20120724-T192810") < 0);
      rawNoiseRemoved.removeIf(s -> getTime(s).compareTo("D20120725-T004607") > 0);
    }
    // SH1305, Bell_M._Shimada, SaKe_2013-D20130729-T161646.raw to SaKe_2013-D20130730-T015635.raw
    if (message.getShipName().equals("Bell_M._Shimada") && message.getCruiseName().equals("SH1305")) {
      LOGGER.info("Filtering to subset for Bell M Shimada SH1305");
      rawNoiseRemoved.removeIf(s -> getTime(s).compareTo("D20130729-T161646") < 0);
      rawNoiseRemoved.removeIf(s -> getTime(s).compareTo("D20130730-T015635") > 0);
    }
    // SH1507, Bell_M._Shimada, SaKe2015-D20150719-T190837 to SaKe2015-D20150719-T195842
    if (message.getShipName().equals("Bell_M._Shimada") && message.getCruiseName().equals("SH1507")) {
      LOGGER.info("Filtering to subset for Bell M Shimada SH1507");
      rawNoiseRemoved.removeIf(s -> getTime(s).compareTo("D20150719-T190837") < 0);
      rawNoiseRemoved.removeIf(s -> getTime(s).compareTo("D20150719-T195842") > 0);
    }

    return rawNoiseRemoved;
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
    record.setPipelineStatus(FileInfoRecord.PipelineStatus.PROCESSING_CRUISE_SPLITTER);
    mapper.save(record, DynamoDBMapperConfig.TableNameOverride.withTableNameReplacement(configuration.getTableName()).config());
  }

}
