package edu.colorado.cires.cmg.echofish.aws.lambda.cruisesplit;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import edu.colorado.cires.cmg.echofish.data.s3.S3OperationsImpl;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled
public class GetRealFilesTest {


  @Test
  public void test() throws Exception {
    Path path = Paths.get("target/SH1305.txt");
    Files.createDirectories(Paths.get("target"));

    AmazonS3 s3 = AmazonS3ClientBuilder.standard().withCredentials(new AWSCredentialsProvider() {
      @Override
      public AWSCredentials getCredentials() {
        return null;
      }

      @Override
      public void refresh() {

      }
    }).withRegion(Regions.US_EAST_1).build();
    S3OperationsImpl s3Operations = new S3OperationsImpl(s3);
    Files.write(path, s3Operations.listObjects("noaa-wcsd-pds", "data/raw/Bell_M._Shimada/SH1305/EK60/"), StandardCharsets.UTF_8);
  }
}
