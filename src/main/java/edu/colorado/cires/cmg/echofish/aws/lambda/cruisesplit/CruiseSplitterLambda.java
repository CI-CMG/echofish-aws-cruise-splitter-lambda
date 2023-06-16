package edu.colorado.cires.cmg.echofish.aws.lambda.cruisesplit;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.sns.AmazonSNSClientBuilder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.colorado.cires.cmg.echofish.data.model.CruiseProcessingMessage;
import edu.colorado.cires.cmg.echofish.data.model.jackson.ObjectMapperCreator;
import edu.colorado.cires.cmg.echofish.data.s3.S3OperationsImpl;
import edu.colorado.cires.cmg.echofish.data.sns.SnsNotifierFactoryImpl;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;

public class CruiseSplitterLambda implements RequestHandler<SNSEvent, Void> {
    private static final Logger LOGGER = LoggerFactory.getLogger(CruiseSplitterLambda.class);


    static {
        Map<String, String> map = new HashMap<>();
        map.put("INPUT_BUCKET", System.getenv("INPUT_BUCKET"));
        map.put("TOPIC_ARN", System.getenv("TOPIC_ARN"));
        map.put("TABLE_NAME", System.getenv("TABLE_NAME"));
        map.put("BUCKET_REGION", System.getenv("BUCKET_REGION"));
        LOGGER.info("ENV {}", map);
    }

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperCreator.create();
    private static final CruiseSplitterLambdaHandler HANDLER = new CruiseSplitterLambdaHandler(
            new S3OperationsImpl(AmazonS3ClientBuilder.standard().withRegion(Objects.requireNonNull(System.getenv("BUCKET_REGION"))).build()),
            new SnsNotifierFactoryImpl(OBJECT_MAPPER, AmazonSNSClientBuilder.defaultClient()),
            AmazonDynamoDBClientBuilder.standard().build(),
            new CruiseSplitterLambdaConfiguration(
                    Objects.requireNonNull(System.getenv("INPUT_BUCKET")),
                    Objects.requireNonNull(System.getenv("TOPIC_ARN")),
                    Objects.requireNonNull(System.getenv("TABLE_NAME"))),
            Instant::now);

    @Override
    public Void handleRequest(SNSEvent snsEvent, Context context) {

        LOGGER.info("Received event: {}", snsEvent);

        CruiseProcessingMessage cruiseProcessingMessage;
        try {
            cruiseProcessingMessage = OBJECT_MAPPER.readValue(snsEvent.getRecords().get(0).getSNS().getMessage(), CruiseProcessingMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to parse SNS notification", e);
        }

        HANDLER.handleRequest(cruiseProcessingMessage);

        return null;
    }
}
