package edu.colorado.cires.cmg.echofish.aws.lambda.cruisesplit;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBScanExpression;
import com.amazonaws.services.dynamodbv2.local.embedded.DynamoDBEmbedded;
import com.amazonaws.services.dynamodbv2.model.*;
import edu.colorado.cires.cmg.echofish.data.dynamo.FileInfoRecord.PipelineStatus;
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

    // For testing select set of cruises that we only want to process a subset of
    // MF0710, Miller_Freeman, HAKE2007-D20070708-T010210.raw to HAKE2007-D20070708-T200449.raw
    // MF0903, Miller_Freeman, MF2009-D20090724-T015244.raw to MF2009-D20090724-T183253.raw
    // SH1103, Bell_M._Shimada, HAKE2011-D20110721-T203942.raw to HAKE2011-D20110722-T005319.raw
    // SH1204, Bell_M._Shimada, SaKe_2012-D20120724-T192810.raw to SaKe_2012-D20120725-T004607.raw
    // SH1305, Bell_M._Shimada, SaKe_2013-D20130729-T161646.raw to SaKe_2013-D20130730-T015635.raw
    // SH1507, Bell_M._Shimada, SaKe2015-D20150719-T190837 to SaKe2015-D20150719-T195842


    // MF0710, Miller_Freeman, HAKE2007-D20070708-T010210.raw to HAKE2007-D20070708-T200449.raw
    private static final List<String> FILES_MF0710 = Arrays.asList(
        "NOISE-D20070620-T124720.raw", // there are not actually NOISE files for MF0710
        "NOISE-D20070821-T225940.raw",
        "HAKE2007-D20070620-T124720.raw",
        "HAKE2007-D20070620-T132735.raw",
        "HAKE2007-D20070708-T010210.raw", // start subset
        "HAKE2007-D20070708-T011210.raw",
        "HAKE2007-D20070708-T200449.raw", // end subset
        "HAKE2007-D20070821-T225940.raw",
        "README_MF0710_EK60.md"
    );
    // MF0903, Miller_Freeman, MF2009-D20090724-T015244.raw to MF2009-D20090724-T183253.raw
    private static final List<String> FILES_MF0903 = Arrays.asList(
        "MF2009-D20090629-T052604.raw",
        "MF2009-D20090724-T015244.raw", // start subset
        "MF2009-D20090724-T016244.raw",
        "MF2009-D20090724-T183253.raw", // end subset
        "MF2009-D20090823-T123042.raw"
    );
    // SH1103, Bell_M._Shimada, HAKE2011-D20110721-T203942.raw to HAKE2011-D20110722-T005319.raw
    private static final List<String> FILES_SH1103 = Arrays.asList(
        "HAKE2011-D20110626-T132001.raw",
        "HAKE2011-D20110721-T203942.raw", // start subset
        "HAKE2011-D20110721-T213942.raw",
        "HAKE2011-D20110722-T005319.raw", // end subset
        "HAKE2011-D20110831-T045323.raw"
    );
    // SH1204, Bell_M._Shimada, SaKe_2012-D20120724-T192810.raw to SaKe_2012-D20120725-T004607.raw
    private static final List<String> FILES_SH1204 = Arrays.asList(
        "SaKe_2012-D20120625-T043038.raw",
        "SaKe_2012-D20120724-T192810.raw", // start subset
        "SaKe_2012-D20120724-T202810.raw",
        "SaKe_2012-D20120725-T004607.raw", // end subset
        "SaKe_2012-D20120824-T041706.raw"
    );
    // SH1305, Bell_M._Shimada, SaKe_2013-D20130729-T161646.raw to SaKe_2013-D20130730-T015635.raw
    private static final List<String> FILES_SH1305 = Arrays.asList(
        "SaKe2013-D20130522-T134850.raw",
        "SaKe_2013-D20130729-T161646.raw", // start subset
        "SaKe_2013-D20130729-T171646.raw",
        "SaKe_2013-D20130730-T015635.raw", // end subset
        "SaKe_2013-D20130829-T210932.raw"
    );
    // SH1507, Bell_M._Shimada, SaKe2015-D20150719-T190837 to SaKe2015-D20150719-T195842
    private static final List<String> FILES_SH1507 = Arrays.asList(
        "NOISE-D20150620-T000936.raw",
        "NOISE-D20150620-T152146.raw",
        "NOISE-D20150620-T155147.raw",
        "NOISE-D20150910-T130751.raw",
        "SaKe2015-D20150619-T230939.raw",
        "SaKe2015-D20150719-T190837.raw", // start subset
        "SaKe2015-D20150719-T190937.raw",
        "SaKe2015-D20150719-T195842.raw", // end subset
        "SaKe2015-D20150910-T201936.raw"
    );

    @BeforeEach
    public void before() throws Exception {
        System.setProperty("sqlite4java.library.path", "native-libs");
        S3TestUtils.cleanupMockS3Directory(INPUT_BUCKET);
//        Path parent = INPUT_BUCKET.resolve("data/raw/Henry_B._Bigelow/HB0707/EK60");

//        for (String file : FILES) {
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

      Path parent = INPUT_BUCKET.resolve("data/raw/Miller_Freeman/MF0710/EK60");
      Files.createDirectories(parent);
      for (String file : FILES_MF0710 ) {
        Files.write(parent.resolve(file), new byte[0]);
      }

        SnsNotifier snsNotifier = mock(SnsNotifier.class);
        when(sns.createNotifier()).thenReturn(snsNotifier);


        CruiseProcessingMessage message = new CruiseProcessingMessage();
        // HB0707, Henry_B._Bigelow
//        message.setCruiseName("HB0707");
//        message.setShipName("Henry_B._Bigelow");

        // MF0710, Miller_Freeman
        message.setCruiseName("MF0710");
        message.setShipName("Miller_Freeman");
        //
        message.setSensorName("EK60");

        handler.handleRequest(message);

//        List<FileInfoRecord> expected = FILES.stream()
        List<FileInfoRecord> expected = FILES_MF0710.stream()
                .filter(file -> file.endsWith(".raw") && !file.contains("NOISE"))
                .filter(file -> file.compareToIgnoreCase("HAKE2007-D20070708-T010210.raw") >= 0)
                .filter(file -> file.compareToIgnoreCase("HAKE2007-D20070708-T200449.raw") <= 0)
                .map(file -> {
                    FileInfoRecord record = new FileInfoRecord();
                    //// HB0707, Henry_B._Bigelow ////
                    //record.setCruiseName("HB0707");
                    //record.setShipName("Henry_B._Bigelow");
                    //// MF0710, Miller_Freeman ////
                    record.setCruiseName("MF0710");
                    record.setShipName("Miller_Freeman");
                    //
                    record.setSensorName("EK60");
                    record.setPipelineStatus(PipelineStatus.PROCESSING_CRUISE_SPLITTER);
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

        expectedMessages.forEach(expectedMessage -> verify(snsNotifier).notify(eq(TOPIC_ARN), eq(expectedMessage)));
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


    @Test
    public void testSh1305() throws Exception {

      Path parent = INPUT_BUCKET.resolve("data/raw/Bell_M._Shimada/SH1305/EK60");
      Files.createDirectories(parent);
      for (String file : Files.readAllLines(Paths.get("src/test/resources/SH1305.txt")) ) {
        Files.write(parent.resolve(file.replaceFirst("data/raw/Bell_M._Shimada/SH1305/EK60/", "")), new byte[0]);
      }

        SnsNotifier snsNotifier = mock(SnsNotifier.class);
        when(sns.createNotifier()).thenReturn(snsNotifier);


        CruiseProcessingMessage message = new CruiseProcessingMessage();
        message.setCruiseName("SH1305");
        message.setShipName("Bell_M._Shimada");
        message.setSensorName("EK60");

        handler.handleRequest(message);

        List<FileInfoRecord> expected = Files.readAllLines(Paths.get("src/test/resources/SH1305-expected.txt")).stream()
            .map(file -> file.replaceFirst("data/raw/Bell_M._Shimada/SH1305/EK60/", ""))
            .map(file -> {
                FileInfoRecord record = new FileInfoRecord();
                record.setCruiseName("SH1305");
                record.setShipName("Bell_M._Shimada");
                record.setSensorName("EK60");
                record.setPipelineStatus(PipelineStatus.PROCESSING_CRUISE_SPLITTER);
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

        expectedMessages.forEach(expectedMessage -> verify(snsNotifier).notify(eq(TOPIC_ARN), eq(expectedMessage)));
    }


}
