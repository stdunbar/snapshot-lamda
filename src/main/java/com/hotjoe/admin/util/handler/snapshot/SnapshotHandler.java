package com.hotjoe.admin.util.handler.snapshot;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.AmazonEC2Exception;
import com.amazonaws.services.ec2.model.CreateSnapshotRequest;
import com.amazonaws.services.ec2.model.CreateSnapshotResult;
import com.amazonaws.services.ec2.model.DeleteSnapshotRequest;
import com.amazonaws.services.ec2.model.DescribeSnapshotsRequest;
import com.amazonaws.services.ec2.model.DescribeSnapshotsResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.ResourceType;
import com.amazonaws.services.ec2.model.Snapshot;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TagSpecification;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotjoe.admin.util.handler.snapshot.version.VersionInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


/**
 * A class to create a snapshot of a EBS volume.  The expected input is a JSON string, looking something like:
 * <pre>
 * {
 *     "volumeId": "vol-0c4077e79d2c5034d",
 *     "description":"Jenkins Snapshot",
 *     "name": "jenkins-snapshot"
 * }
 * </pre>
 *
 * Two environment variables can optionally be set for the Lambda:
 * <ol>
 *     <li><b>REGION</b> - allows you to override the region that the volume and snapshot live in.  Defaults
 *     to the same region that the Lambda is running in.</li>
 *     <li><b>NUM_SNAPSHOTS_TO_KEEP</b> - an integer number of the number of old snapshots to keep.  Defaults
 *     to 10</li>
 * </ol>
 *
 */
public class SnapshotHandler implements RequestStreamHandler  {

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

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(inputStream);
        String description = "Created by SnapshotHandler Lambda: " + jsonNode.get("description").asText();
        String volumeId = jsonNode.get("volumeId").asText();
        String name = jsonNode.get("name").asText();

        lambdaLogger.log("in handlerRequest:");
        lambdaLogger.log("description is \"" + description + "\"");
        lambdaLogger.log("volumeId is \"" + volumeId + "\"");
        lambdaLogger.log("name is \"" + name + "\"");
        lambdaLogger.log("version info - " + new VersionInfo().getVersionString() );

        //
        // you can override the region if needed
        //
        Region region;
        String regionEnvVar = System.getenv("REGION");
        if (regionEnvVar != null)
            region = RegionUtils.getRegion(regionEnvVar);
        else
            region = RegionUtils.getRegion(System.getenv("AWS_REGION"));

        AmazonEC2 amazonEC2 = AmazonEC2ClientBuilder.standard().withRegion(region.getName()).build();

        //
        // create the actual snapshot
        //
        CreateSnapshotRequest createSnapshotRequest = new CreateSnapshotRequest()
                .withDescription(description)
                .withVolumeId(volumeId)
                .withTagSpecifications(new TagSpecification().withTags(new Tag("Name", name)).withResourceType(ResourceType.Snapshot));
        CreateSnapshotResult createSnapshotResult = amazonEC2.createSnapshot(createSnapshotRequest);

        lambdaLogger.log("created snapshot request, snapshot id is \"" + createSnapshotResult.getSnapshot().getSnapshotId() + "\"");

        //
        // now, do we have anything to clean up?
        //
        Filter filter = new Filter().withName("volume-id").withValues(volumeId);

        List<Snapshot> snapshots = new ArrayList<>();

        String nextToken = null;
        do {
            DescribeSnapshotsRequest describeSnapshotsRequest = new DescribeSnapshotsRequest().withFilters(filter);
            if (nextToken != null)
                describeSnapshotsRequest.setNextToken(nextToken);

            DescribeSnapshotsResult describeSnapshotsResult = amazonEC2.describeSnapshots(describeSnapshotsRequest);

            snapshots.addAll(describeSnapshotsResult.getSnapshots());

            nextToken = describeSnapshotsResult.getNextToken();

        } while (nextToken != null);

        snapshots.sort(Comparator.comparing(Snapshot::getStartTime));

        lambdaLogger.log("found " + snapshots.size() + " existing snapshots for volume id " + volumeId);

        int snapshotsToKeep = 10;
        String snapshotsToKeepString = System.getenv("NUM_SNAPSHOTS_TO_KEEP");
        if (snapshotsToKeepString != null)
            snapshotsToKeep = Integer.parseInt(snapshotsToKeepString);

        if (snapshotsToKeep >= snapshots.size()) {
            lambdaLogger.log("we want to keep " + snapshotsToKeep + " snapshots but only have " + snapshots.size() + " available.  we're done");
            return;
        }

        int numSnapshotsToRemove = snapshots.size() - snapshotsToKeep;

        List<Snapshot> snapshotsToRemove = snapshots.subList(0, numSnapshotsToRemove);

        lambdaLogger.log("removing " + numSnapshotsToRemove + " old snapshot" + (numSnapshotsToRemove > 1 ? "s": ""));

        for( Snapshot nextRemovedSnapshot: snapshotsToRemove ) {
            try {
                amazonEC2.deleteSnapshot(new DeleteSnapshotRequest(nextRemovedSnapshot.getSnapshotId()));
            }
            catch( AmazonEC2Exception amazonEC2Exception ) {
                lambdaLogger.log( "error removing snapshot id " + nextRemovedSnapshot.getSnapshotId() + " - is it in use?  it will be skipped.  error is " + amazonEC2Exception.getMessage() );
            }
        }

        lambdaLogger.log("done with run, remaining time in ms is " + context.getRemainingTimeInMillis() );
    }
}
