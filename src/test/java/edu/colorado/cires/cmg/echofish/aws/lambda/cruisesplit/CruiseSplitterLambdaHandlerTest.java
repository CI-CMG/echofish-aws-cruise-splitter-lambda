package edu.colorado.cires.cmg.echofish.aws.lambda.cruisesplit;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBScanExpression;
import com.amazonaws.services.dynamodbv2.local.embedded.DynamoDBEmbedded;
import com.amazonaws.services.dynamodbv2.model.*;
import edu.colorado.cires.cmg.echofish.aws.test.MockS3Operations;
import edu.colorado.cires.cmg.echofish.aws.test.S3TestUtils;
import edu.colorado.cires.cmg.echofish.data.dynamo.FileInfoRecord;
import edu.colorado.cires.cmg.echofish.data.model.CruiseProcessingMessage;
import edu.colorado.cires.cmg.echofish.data.s3.S3Operations;
import edu.colorado.cires.cmg.echofish.data.sns.SnsNotifier;
import edu.colorado.cires.cmg.echofish.data.sns.SnsNotifierFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CruiseSplitterLambdaHandlerTest {

  private static final String TABLE_NAME = "FILE_INFO";
  private static final String TOPIC_ARN = "MOCK_TOPIC";
  private static final Path INPUT_BUCKET = Paths.get("src/test/resources/input").toAbsolutePath();
  private static final Instant TIME = Instant.now();


  private AmazonDynamoDB dynamo;
  private CruiseSplitterLambdaHandler handler;
  private final S3Operations s3 = new MockS3Operations();
  private SnsNotifierFactory sns;
  private DynamoDBMapper mapper;


  private static final List<String> FILES = Arrays.asList(
      "D20070711-T182032.idx",
      "D20070711-T182032.raw",
      "D20070712-T152416.bot",
      "D20070712-T152416.raw",
      "D20070712-T201647.raw",
      "NOISE_D20070712-T124906.raw"
  );

  @BeforeEach
  public void before() throws Exception {
    System.setProperty("sqlite4java.library.path", "native-libs");
    S3TestUtils.cleanupMockS3Directory(INPUT_BUCKET);
    Path parent = INPUT_BUCKET.resolve("data/raw/Henry_B._Bigelow/HB0707/EK60");
    Files.createDirectories(parent);
    for (String file : FILES) {
      Files.write(parent.resolve(file), new byte[0]);
    }
    dynamo = DynamoDBEmbedded.create().amazonDynamoDB();
    mapper = new DynamoDBMapper(dynamo);
    sns = mock(SnsNotifierFactory.class);
    handler = new CruiseSplitterLambdaHandler(
        s3,
        sns,
        dynamo,
        new CruiseSplitterLambdaConfiguration(
            INPUT_BUCKET.toString(),
            TOPIC_ARN,
            TABLE_NAME
        ), () -> TIME);
    createTable(dynamo, TABLE_NAME, "FILE_NAME", "CRUISE_NAME");
  }

  @AfterEach
  public void after() throws Exception {
    S3TestUtils.cleanupMockS3Directory(INPUT_BUCKET);
    dynamo.shutdown();
  }

  @Test
  public void testEmptyDb() throws Exception {
    SnsNotifier snsNotifier = mock(SnsNotifier.class);
    when(sns.createNotifier()).thenReturn(snsNotifier);


    CruiseProcessingMessage message = new CruiseProcessingMessage();
    message.setCruiseName("HB0707");
    message.setShipName("Henry_B._Bigelow");
    message.setSensorName("EK60");

    handler.handleRequest(message);

    List<FileInfoRecord> expected = FILES.stream()
        .filter(file -> file.endsWith(".raw") && !file.contains("NOISE"))
        .map(file -> {
          FileInfoRecord record = new FileInfoRecord();
          record.setCruiseName("HB0707");
          record.setShipName("Henry_B._Bigelow");
          record.setSensorName("EK60");
          record.setPipelineStatus("PROCESSING");
          record.setPipelineTime(TIME.toString());
          record.setFileName(file);
          return record;
        }).collect(Collectors.toList());

    Set<FileInfoRecord> saved = mapper.scan(FileInfoRecord.class, new DynamoDBScanExpression(),
        DynamoDBMapperConfig.TableNameOverride.withTableNameReplacement(TABLE_NAME).config()).stream().collect(Collectors.toSet());

    assertEquals(new HashSet<>(expected), saved);

    List<CruiseProcessingMessage> expectedMessages = expected.stream()
        .map(record -> {
          CruiseProcessingMessage expectedMessage = new CruiseProcessingMessage();
          expectedMessage.setCruiseName(record.getCruiseName());
          expectedMessage.setShipName(record.getShipName());
          expectedMessage.setSensorName(record.getSensorName());
          expectedMessage.setFileName(record.getFileName());
          return expectedMessage;
        }).collect(Collectors.toList());

    expectedMessages.forEach( expectedMessage -> verify(snsNotifier).notify(eq(TOPIC_ARN), eq(expectedMessage)));
  }

  private static CreateTableResult createTable(AmazonDynamoDB ddb, String tableName, String hashKeyName, String rangeKeyName) {
    List<AttributeDefinition> attributeDefinitions = new ArrayList<>();
    attributeDefinitions.add(new AttributeDefinition(hashKeyName, ScalarAttributeType.S));
    attributeDefinitions.add(new AttributeDefinition(rangeKeyName, ScalarAttributeType.S));

    List<KeySchemaElement> ks = new ArrayList<>();
    ks.add(new KeySchemaElement(hashKeyName, KeyType.HASH));
    ks.add(new KeySchemaElement(rangeKeyName, KeyType.RANGE));

    ProvisionedThroughput provisionedthroughput = new ProvisionedThroughput(1000L, 1000L);

    CreateTableRequest request =
        new CreateTableRequest()
            .withTableName(tableName)
            .withAttributeDefinitions(attributeDefinitions)
            .withKeySchema(ks)
            .withProvisionedThroughput(provisionedthroughput);

    return ddb.createTable(request);
  }

}