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

import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import de.ugoe.cs.BugFixClassifier;
import de.ugoe.cs.smartshark.model.Commit;
import de.ugoe.cs.smartshark.model.CommitChanges;
import de.ugoe.cs.smartshark.model.TravisBuild;
import de.ugoe.cs.smartshark.model.VCSSystem;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.types.ObjectId;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.mongodb.morphia.Datastore;
import org.mongodb.morphia.Morphia;

/**
 * @author Fabian Trautsch
 */
public class SmartSHARKPlugin {
    private static final Logger LOGGER = LogManager.getLogger(SmartSHARKPlugin.class.getName());

    private final Datastore datastore;
    private final VCSSystem vcsSystem;
    private Path vcsDirectory;
    private Repository originalRepo;

    public SmartSHARKPlugin(CLIArguments cliArguments) throws IOException, GitAPIException {
        // Initialize db connection + values
        final Morphia morphia = new Morphia();
        morphia.mapPackage("de.ugoe.cs.smartshark.model");

        MongoClientURI uri = new MongoClientURI(Utils.createMongoDBURI(cliArguments.getUsername(),
                cliArguments.getPassword(), cliArguments.getHost(), cliArguments.getPort(),
                cliArguments.getAuthenticationDB(), cliArguments.getSSLEnabled()));
        MongoClient mongoClient = new MongoClient(uri);
        datastore = morphia.createDatastore(mongoClient, cliArguments.getDatabase());


        // Clone the repository for working with it later on, so that we do not need to clone it after
        // each use
        vcsSystem = datastore.createQuery(VCSSystem.class)
                .field("url").equal(cliArguments.getVCSSystemURL()).get();

        String[] parts = vcsSystem.getUrl().split("/");
        String projectName = parts[parts.length-1];

        vcsDirectory = Files.createTempDirectory(projectName);
        originalRepo = Git.cloneRepository()
                .setURI(vcsSystem.getUrl())
                .setDirectory(vcsDirectory.toFile())
                .call().getRepository();
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
                    Map<ObjectId, Map<String, Integer>> changes = getBugClassifications(foundCommit.getRevisionHash(), commit.getRevisionHash());
                    storeResultInMongoDB(foundCommit.getId(), commit.getId(), changes);
                } catch (IOException | GitAPIException e) {
                    LOGGER.warn("Could not get classification for commits {} and {}: "+e.getMessage(),
                            foundCommit.getRevisionHash(), commit.getRevisionHash());
                }
            }
        }
    }

    private Map<ObjectId, Map<String, Integer>> getBugClassifications(String commit1Hash, String commit2Hash) throws IOException, GitAPIException {
        // 1) Copy vcsDirectory to two different locations
        Path commit1Location = Files.createTempDirectory("bc");
        FileUtils.copyDirectory(vcsDirectory.toFile(), commit1Location.toFile());

        Path commit2Location = Files.createTempDirectory("bc");
        FileUtils.copyDirectory(vcsDirectory.toFile(), commit2Location.toFile());

        // 2) checkout to revision
        Git commit1Repo = Git.open(commit1Location.toFile());
        commit1Repo.checkout().setName(commit1Hash).call();

        Git commit2Repo = Git.open(commit2Location.toFile());
        commit2Repo.checkout().setName(commit2Hash).call();

        // 3) get all changed files between these revisions
        ObjectReader reader = originalRepo.newObjectReader();
        CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
        org.eclipse.jgit.lib.ObjectId oldTree = originalRepo.resolve(commit1Hash+"^{tree}");
        oldTreeIter.reset(reader, oldTree);

        CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
        org.eclipse.jgit.lib.ObjectId newTree = originalRepo.resolve(commit2Hash+"^{tree}");
        newTreeIter.reset(reader, newTree);

        DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE);
        diffFormatter.setRepository(originalRepo);
        List<DiffEntry> entries = diffFormatter.scan(oldTreeIter, newTreeIter);

        // Go through the diff between these commits and calculate for each changed file and each change the change
        // type
        Map<ObjectId, Map<String, Integer>> classifications = new HashMap<>();
        for(DiffEntry entry : entries) {
            File oldFile = Paths.get(commit1Location.toString(), entry.getOldPath()).toFile();
            File newFile = Paths.get(commit2Location.toString(), entry.getNewPath()).toFile();


            // We can not distill changes, if there are none -> new file was added here. Maybe interface change?
            if(entry.getOldPath().equals("/dev/null") || entry.getNewPath().equals("/dev/null") ||
                    !entry.getOldPath().endsWith(".java") || !entry.getNewPath().endsWith(".java")) {
                LOGGER.debug("Skipping comparison of files {} and {}.", entry.getOldPath(), entry.getNewPath());
                continue;
            }

            // Get files from database
            de.ugoe.cs.smartshark.model.File dbFile = datastore.createQuery(de.ugoe.cs.smartshark.model.File.class)
                    .field("vcs_system_id").equal(vcsSystem.getId())
                    .field("path").equal(entry.getNewPath())
                    .get();


            Map<String, Integer> results = BugFixClassifier.getBugClassifications(oldFile.toPath(), newFile.toPath());

            // If we could not distill changes, we declare it as other
            if(results.isEmpty()) {
                results =  new HashMap<String, Integer>(){{put("OTHER", 1);}};
            }

            classifications.put(dbFile.getId(), results);
        }

        // Delete temporary folder
        FileUtils.deleteDirectory(commit1Location.toFile());
        FileUtils.deleteDirectory(commit2Location.toFile());

        LOGGER.debug("Final result for changes between commit {} and {}: {}", commit1Hash, commit2Hash,
                classifications);

        return classifications;
    }

    public void storeDataViaAllCommits() {
        List<Commit> commits = datastore.createQuery(Commit.class)
                .field("vcs_system_id").equal(vcsSystem.getId())
                .order("-committer_date").asList();

        storeDataOfCommits(commits);
    }

    public void storeSingleData(String sha1, String sha2) {
        LOGGER.info("Comparing commits {} and {}.", sha1, sha2);
        try {
            Map<ObjectId, Map<String, Integer>> changes = getBugClassifications(sha1, sha2);

            Commit commit1 = datastore.createQuery(Commit.class)
                    .field("vcs_system_id").equal(vcsSystem.getId())
                    .field("revision_hash").equal(sha1)
                    .get();

            Commit commit2 = datastore.createQuery(Commit.class)
                    .field("vcs_system_id").equal(vcsSystem.getId())
                    .field("revision_hash").equal(sha2)
                    .get();

            storeResultInMongoDB(commit1.getId(), commit2.getId(), changes);
        } catch (IOException | GitAPIException e) {
            LOGGER.warn("Could not get classification for commits {} and {}: "+e.getMessage(),
                    sha1, sha2);
        }
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
                Map<ObjectId, Map<String, Integer>> changes = getBugClassifications(commit.getParents().get(0), commit.getRevisionHash());
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

    private void storeResultInMongoDB(ObjectId commitId, ObjectId commitId2, Map<ObjectId, Map<String, Integer>> changes) {
        // We set empty changes to null for the ORM framework
        if(changes.isEmpty()) {
            changes = null;
        }

        CommitChanges commitChanges = datastore.createQuery(CommitChanges.class)
                .field("old_commit_id").equal(commitId)
                .field("new_commit_id").equal(commitId2)
                .get();

        if(commitChanges == null) {
            commitChanges = new CommitChanges();
            commitChanges.setOldCommitId(commitId);
            commitChanges.setNewCommitId(commitId2);
        }

        commitChanges.setClassification(changes);
        datastore.save(commitChanges);
    }


}
