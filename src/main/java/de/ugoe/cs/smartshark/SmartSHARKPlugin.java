/*
 * Copyright (C) 2017 University of Goettingen, Germany
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.ugoe.cs.smartshark;

import com.lexicalscope.jewel.cli.Option;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import de.ugoe.cs.BugClassifier;
import de.ugoe.cs.smartshark.model.Commit;
import de.ugoe.cs.smartshark.model.CommitChanges;
import de.ugoe.cs.smartshark.model.TravisBuild;
import de.ugoe.cs.smartshark.model.VCSSystem;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.types.ObjectId;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.mongodb.morphia.Datastore;
import org.mongodb.morphia.FindAndModifyOptions;
import org.mongodb.morphia.Morphia;
import org.mongodb.morphia.query.Query;
import org.mongodb.morphia.query.UpdateOperations;

/**
 * @author Fabian Trautsch
 */
public class SmartSHARKPlugin {
    private static final Logger LOGGER = LogManager.getLogger(SmartSHARKPlugin.class.getName());

    private final Datastore datastore;
    private final VCSSystem vcsSystem;
    private final BugClassifier bc;

    public SmartSHARKPlugin(CLIArguments cliArguments) throws IOException, GitAPIException {
        // Initialize db connection + values
        final Morphia morphia = new Morphia();
        morphia.mapPackage("de.ugoe.cs.smartshark.model");

        MongoClientURI uri = new MongoClientURI(Utils.createMongoDBURI(cliArguments.getUsername(),
                cliArguments.getPassword(), cliArguments.getHost(), cliArguments.getPort(),
                cliArguments.getAuthenticationDB(), cliArguments.getSSLEnabled()));
        MongoClient mongoClient = new MongoClient(uri);
        datastore = morphia.createDatastore(mongoClient, cliArguments.getDatabase());


        vcsSystem = datastore.createQuery(VCSSystem.class)
                .field("url").equal(cliArguments.getVCSSystemURL()).get();
        bc = new BugClassifier(cliArguments.getVCSSystemURL());

    }

    public void storeDataViaTravis() {
        List<TravisBuild> travisBuilds = datastore.createQuery(TravisBuild.class)
                .field("state").notEqual("passed")
                .field("vcs_system_id").equal(vcsSystem.getId())
                .asList();

        LOGGER.debug("Found {} travis builds via vcsSystem {}", travisBuilds.size(), vcsSystem.getUrl());

        for(TravisBuild trBuild: travisBuilds) {
            // Exclude travis builds, where the commit was not mined (e.g., pull requests)
            if(trBuild.getCommitId() == null) {
                continue;
            }

            Commit commit = datastore.createQuery(Commit.class)
                    .field("id").equal(trBuild.getCommitId()).get();

            Commit foundCommit = getCommitFromPreviousSuccessfulBuild(commit);

            // WorkingCommitHash can be null, if we encounter a merge commit
            if(foundCommit != null) {
                LOGGER.info("Comparing commits {} and {}.", foundCommit.getRevisionHash(), commit.getRevisionHash());
                try {
                    Map<String, Integer> changes = bc.getBugClassifications(foundCommit.getRevisionHash(), commit.getRevisionHash());
                    storeResultInMongoDB(foundCommit.getId(), commit.getId(), changes);
                } catch (IOException | GitAPIException e) {
                    LOGGER.warn("Could not get classification for commits {} and {}: "+e.getMessage(),
                            foundCommit.getRevisionHash(), commit.getRevisionHash());
                }
            }
        }
    }

    public void storeDataViaAllCommits() {
        List<Commit> commits = datastore.createQuery(Commit.class)
                .field("vcs_system_id").equal(vcsSystem.getId())
                .order("-committer_date").asList();

        storeDataOfCommits(commits);
    }

    public void storeDataViaBugfixCommits() {
        List<Commit> commits = datastore.createQuery(Commit.class)
                .field("vcs_system_id").equal(vcsSystem.getId())
                .field("labels.adjustedszz_bugfix").equal(true)
                .order("-committer_date").asList();

        storeDataOfCommits(commits);
    }

    private void storeDataOfCommits(List<Commit> commits) {
        for(Commit commit: commits) {
            // We always chose the first parent --> we expect that developers have merged the feature branch in the master branch
            LOGGER.info("Comparing commits {} and {}.", commit.getParents().get(0), commit.getRevisionHash());
            try {
                Map<String, Integer> changes = bc.getBugClassifications(commit.getParents().get(0), commit.getRevisionHash());
                Commit parentCommit = datastore.createQuery(Commit.class)
                        .field("revision_hash").equal(commit.getParents().get(0))
                        .field("vcs_system_id").equal(vcsSystem.getId())
                        .get();
                storeResultInMongoDB(parentCommit.getId(), commit.getId(), changes);
            } catch (IOException | GitAPIException e) {
                LOGGER.warn("Could not get classification for commits {} and {}: "+e.getMessage(),
                        commit.getParents().get(0), commit.getRevisionHash());
            }
        }
    }

    private Commit getCommitFromPreviousSuccessfulBuild(Commit startCommit) {
        Commit foundCommit = null;
        Commit previousCommit = startCommit;

        while(foundCommit == null) {
            // We always chose the first parent --> we expect that developers have merged the feature branch in the master branch
            Commit parentCommit = datastore.createQuery(Commit.class)
                    .field("vcs_system_id").equal(vcsSystem.getId())
                    .field("revision_hash").equal(previousCommit.getParents().get(0))
                    .get();

            TravisBuild trBuild = datastore.createQuery(TravisBuild.class)
                    .field("vcs_system_id").equal(vcsSystem.getId())
                    .field("state").equal("passed")
                    .field("commit_id").equal(parentCommit.getId())
                    .get();

            if(trBuild != null) {
                foundCommit = parentCommit;
            } else {
                previousCommit = parentCommit;
            }
        }

        return foundCommit;
    }

    private void storeResultInMongoDB(ObjectId commitId, ObjectId commitId2, Map<String, Integer> changes) {
        if(changes.isEmpty()) {
            return;
        }

        Query<CommitChanges> query = datastore.find(CommitChanges.class)
                .field("old_commit_id").equal(commitId)
                .field("new_commit_id").equal(commitId2);
        UpdateOperations<CommitChanges> updateOperations = datastore.createUpdateOperations(CommitChanges.class)
                .set("changes", changes);
        datastore.findAndModify(query, updateOperations, new FindAndModifyOptions().returnNew(false).upsert(true));
    }


}
