package com.hotjoe.admin.snapshot;


import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotjoe.admin.snapshot.version.VersionInfo;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateSnapshotRequest;
import software.amazon.awssdk.services.ec2.model.CreateSnapshotResponse;
import software.amazon.awssdk.services.ec2.model.DeleteSnapshotRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSnapshotsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSnapshotsResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Snapshot;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


/**
 * A class to create a snapshot of an EBS volume.  The expected input is a JSON string, looking something like:
 * <pre>
 * {
 *     "volumeId": "vol-0c4077e79d2c5034d",
 *     "description":"Jenkins Snapshot",
 *     "name": "jenkins-snapshot",
 *     "numSnapshotsToKeep", "10
 * }
 * </pre>
 *
 * Note that the numSnapshotsToKeep value is optional.  If it exists it will override the environment variable below.
 * This allows you to have multiple environment with, for example, your dev environment only keeping a few days of
 * backups and your production keeping more.
 *
 * Two environment variables can optionally be set for the Lambda:
 * <ol>
 *     <li><b>REGION</b> - allows you to override the region that the volume and snapshot live in.  Defaults
 *     to the same region that the Lambda is running in.</li>
 *     <li><b>NUM_SNAPSHOTS_TO_KEEP</b> - an integer number of the number of old snapshots to keep.  Defaults
 *     to 10.  Again, if a value is provided in the JSON input then it will take precedence.</li>
 * </ol>
 *
 */
public class SnapshotHandler implements RequestStreamHandler  {
    private static final int NUM_SNAPSHOTS_TO_KEEP_DEFAULT = 10;

    /**
     * This is the method called by the AWS Lambda service.
     *
     * @param inputStream provided by the Lambda service, this is a stream of data provided to the Lambda function
     * @param outputStream provided by the Lambda service, this is a stream of data that can return values.  It is
     *                     unused in this method as there is no output.
     * @param context the Lambda context that this method was called with.
     **
     * @throws IOException if there was a problem running the method.
     *
     */

    @Override
    public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) throws IOException {
        LambdaLogger lambdaLogger = context.getLogger();

        int snapshotsToKeep = NUM_SNAPSHOTS_TO_KEEP_DEFAULT;

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(inputStream);
        String description = "Created by SnapshotHandler Lambda: " + jsonNode.get("description").asText();
        String volumeId = jsonNode.get("volumeId").asText();
        String name = jsonNode.get("name").asText();
        JsonNode numSnapshotsToKeepNode = jsonNode.get("numSnapshotsToKeep");
        if( numSnapshotsToKeepNode != null ) {
            snapshotsToKeep = Integer.parseInt(numSnapshotsToKeepNode.asText());
        }
        else {
              String snapshotsToKeepString = System.getenv("NUM_SNAPSHOTS_TO_KEEP");
              if (snapshotsToKeepString != null)
                  snapshotsToKeep = Integer.parseInt(snapshotsToKeepString);

            lambdaLogger.log("snapshotsToKeep is " + snapshotsToKeep );
        }

        lambdaLogger.log("in handlerRequest:");
        lambdaLogger.log("description is \"" + description + "\"");
        lambdaLogger.log("volumeId is \"" + volumeId + "\"");
        lambdaLogger.log("name is \"" + name + "\"");
        lambdaLogger.log("snapshotsToKeep is " + snapshotsToKeep );
        lambdaLogger.log("version info is:" + new VersionInfo().getVersionString() );

        //
        // you can override the region if needed
        //
        Region region;
        String regionEnvVar = System.getenv("REGION");
        if (regionEnvVar != null)
            region = Region.of(regionEnvVar);
        else
            region = Region.of(System.getenv("AWS_REGION"));

        try( Ec2Client ec2Client = Ec2Client.builder().region(region).build() ) {
            //
            // create the snapshot
            //
            CreateSnapshotRequest createSnapshotRequest = CreateSnapshotRequest.builder()
                    .description(description)
                    .volumeId(volumeId)
                    .tagSpecifications(TagSpecification.builder().tags(Tag.builder().key("Name").value(name).build()).resourceType(ResourceType.SNAPSHOT).build())
                    .build();

            CreateSnapshotResponse createSnapshotResponse = ec2Client.createSnapshot(createSnapshotRequest);

            lambdaLogger.log("created snapshot request, snapshot id is \"" + createSnapshotResponse.snapshotId() + "\"");

            //
            // now, do we have anything to clean up?
            //
            Filter filter = Filter.builder().name("volume-id").values(volumeId).build();

            List<Snapshot> snapshots = new ArrayList<>();

            String nextToken = null;
            do {
                DescribeSnapshotsRequest describeSnapshotsRequest = DescribeSnapshotsRequest.builder()
                        .filters(filter)
                        .nextToken(nextToken)
                        .build();

                DescribeSnapshotsResponse describeSnapshotsResponse = ec2Client.describeSnapshots(describeSnapshotsRequest);

                snapshots.addAll(describeSnapshotsResponse.snapshots());

                nextToken = describeSnapshotsResponse.nextToken();

            } while (nextToken != null);

            snapshots.sort(Comparator.comparing(Snapshot::startTime));

            lambdaLogger.log("found " + snapshots.size() + " existing snapshots for volume id " + volumeId);




            if (snapshotsToKeep >= snapshots.size()) {
                lambdaLogger.log("we want to keep " + snapshotsToKeep + " snapshots but only have " + snapshots.size() + " available.  we're done");
                return;
            }

            int numSnapshotsToRemove = snapshots.size() - snapshotsToKeep;

            List<Snapshot> snapshotsToRemove = snapshots.subList(0, numSnapshotsToRemove);

            lambdaLogger.log("removing " + numSnapshotsToRemove + " old snapshot" + (numSnapshotsToRemove > 1 ? "s" : ""));

            for (Snapshot nextRemovedSnapshot : snapshotsToRemove) {
                try {
                    ec2Client.deleteSnapshot(DeleteSnapshotRequest.builder().snapshotId(nextRemovedSnapshot.snapshotId()).build());
                } catch (Ec2Exception ec2Exception) {
                    lambdaLogger.log("error removing snapshot id " + nextRemovedSnapshot.snapshotId() + " - is it in use?  it will be skipped.  error is " + ec2Exception.getMessage());
                }
            }

            lambdaLogger.log("done with run, remaining time in ms is " + context.getRemainingTimeInMillis());
        }
        catch( Exception exception) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            exception.printStackTrace(pw);

            lambdaLogger.log( "overall exception:\n" + pw );
        }
    }
}
