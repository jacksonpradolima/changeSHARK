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

package de.ugoe.cs;

import ch.uzh.ifi.seal.changedistiller.ChangeDistiller;
import ch.uzh.ifi.seal.changedistiller.distilling.FileDistiller;
import ch.uzh.ifi.seal.changedistiller.model.entities.SourceCodeChange;
import de.ugoe.cs.bugfixtypes.ComputationChangeTypes;
import de.ugoe.cs.bugfixtypes.DataChangeTypes;
import de.ugoe.cs.bugfixtypes.InterfaceChangeTypes;
import de.ugoe.cs.bugfixtypes.LogicControlChangeTypes;
import de.ugoe.cs.bugfixtypes.OtherChangeTypes;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.UnexpectedException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;

/**
 * @author Fabian Trautsch
 */
public class BugClassifier {
    private static final Logger LOGGER = LogManager.getLogger(BugClassifier.class.getName());

    private Path vcsDirectory;
    private Repository originalRepo;
    private FileDistiller distiller;

    public BugClassifier(String vcsSystemUrl) throws IOException, GitAPIException {
        String[] parts = vcsSystemUrl.split("/");
        String projectName = parts[parts.length-1];

        vcsDirectory = Files.createTempDirectory(projectName);
        originalRepo = Git.cloneRepository().setURI(vcsSystemUrl).setDirectory(vcsDirectory.toFile()).call().getRepository();
        distiller = ChangeDistiller.createFileDistiller(ChangeDistiller.Language.JAVA);
    }

    public Map<String, Integer> getBugClassifications(String commit1Hash, String commit2Hash) throws IOException, GitAPIException {
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
        ObjectId oldTree = originalRepo.resolve(commit1Hash+"^{tree}");
        oldTreeIter.reset(reader, oldTree);

        CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
        ObjectId newTree = originalRepo.resolve(commit2Hash+"^{tree}");
        newTreeIter.reset(reader, newTree);

        DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE);
        diffFormatter.setRepository(originalRepo);
        List<DiffEntry> entries = diffFormatter.scan(oldTreeIter, newTreeIter);

        // Go through the diff between these commits and calculate for each changed file and each change the change
        // type
        Map<String, Integer> classifiedChanges = new HashMap<>();
        for(DiffEntry entry : entries) {
            File oldFile = Paths.get(commit1Location.toString(), entry.getOldPath()).toFile();
            File newFile = Paths.get(commit2Location.toString(), entry.getNewPath()).toFile();

            // We can not distill changes, if there are none -> new file was added here. Maybe interface change?
            if(entry.getOldPath().equals("/dev/null") || entry.getNewPath().equals("/dev/null") ||
                    !entry.getOldPath().endsWith(".java") || !entry.getNewPath().endsWith(".java")) {
                LOGGER.debug("Skipping comparison of files {} and {}.", entry.getOldPath(), entry.getNewPath());
                continue;
            }

            LOGGER.debug("Distilling changes between {} and {}.", oldFile, newFile);

            // Call to changedistiller, sometimes there can be exceptions, but this is very rare
            try {
                distiller.extractClassifiedSourceCodeChanges(oldFile, newFile );
            } catch(Exception e) {
                LOGGER.catching(e);
            }

            // Go thorugh all found changes and classify them according to our new classification schema
            List<SourceCodeChange> changes = distiller.getSourceCodeChanges();
            if(changes != null) {
                for(SourceCodeChange change : changes) {
                    String label;

                    if(isDataChange(change)) {
                        label = "DATA";
                    } else if(isComputationChange(change)) {
                        label = "COMPUTATION";
                    } else if(isInterfaceChange(change)) {
                        label = "INTERFACE";
                    } else if(isLogicControlChange(change)) {
                        label = "LOGIC/CONTROL";
                    } else if(isOtherChange(change)) {
                        label = "OTHER";
                    } else {
                        throw new UnexpectedException("Unexpected Change!");
                    }

                    classifiedChanges.put(label, classifiedChanges.getOrDefault(label, 0)+1);
                    LOGGER.debug("ChangeType: {}, ChangedEntity: {}, ChangedParentEntity: {}, ResultingLabel: {}",
                            change.getChangeType(), change.getChangedEntity(), change.getParentEntity(), label);
                }
            }
        }

        // Delete temporary folder
        FileUtils.deleteDirectory(commit1Location.toFile());
        FileUtils.deleteDirectory(commit2Location.toFile());

        LOGGER.debug("Result: {}", classifiedChanges);

        return classifiedChanges;
    }

    private boolean isDataChange(SourceCodeChange change) {
        for(DataChangeTypes c : DataChangeTypes.values()) {
            if(c.name().equals(change.getChangeType().name())) {
                return true;
            }
        }

        if(change.getChangeType().name().startsWith("STATEMENT_") &&
                change.getChangedEntity().getLabel().equals("VARIABLE_DECLARATION_STATEMENT")) {
            return true;
        }

        if(change.getChangeType().name().equals("UNCLASSIFIED_CHANGE") &&
                change.getChangedEntity().getLabel().equals("MODIFIER")) {
            return true;
        }


        return false;
    }

    private boolean isInterfaceChange(SourceCodeChange change) {
        for(InterfaceChangeTypes c : InterfaceChangeTypes.values()) {
            if(c.name().equals(change.getChangeType().name())) {
                return true;
            }
        }

        if(change.getChangeType().name().startsWith("STATEMENT_") &&
                (change.getChangedEntity().getLabel().equals("METHOD_INVOCATION") ||
                 change.getChangedEntity().getLabel().equals("CONSTRUCTOR_INVOCATION") ||
                 change.getChangedEntity().getLabel().equals("SYNCHRONIZED_STATEMENT"))) {
            return true;
        }

        if(change.getChangeType().name().endsWith("_FUNCTIONALITY") &&
                change.getChangedEntity().getLabel().equals("METHOD")) {
            return true;
        }

        if(change.getChangeType().name().equals("UNCLASSIFIED_CHANGE") &&
                change.getChangedEntity().getLabel().equals("TYPE_PARAMETER")) {
            return true;
        }

        return false;
    }

    private boolean isLogicControlChange(SourceCodeChange change) {
        for(LogicControlChangeTypes c : LogicControlChangeTypes.values()) {
            if(c.name().equals(change.getChangeType().name())) {
                return true;
            }
        }

        if(change.getChangeType().name().startsWith("STATEMENT_") &&
                (change.getChangedEntity().getLabel().equals("IF_STATEMENT") ||
                 change.getChangedEntity().getLabel().equals("FOREACH_STATEMENT") ||
                 change.getChangedEntity().getLabel().equals("CONTINUE_STATEMENT") ||
                 change.getChangedEntity().getLabel().equals("RETURN_STATEMENT") ||
                 change.getChangedEntity().getLabel().equals("THROW_STATEMENT") ||
                 change.getChangedEntity().getLabel().equals("SWITCH_CASE") ||
                 change.getChangedEntity().getLabel().equals("SWITCH_STATEMENT") ||
                 change.getChangedEntity().getLabel().equals("BREAK_STATEMENT") ||
                 change.getChangedEntity().getLabel().equals("CATCH_CLAUSE") ||
                 change.getChangedEntity().getLabel().equals("TRY_STATEMENT") ||
                 change.getChangedEntity().getLabel().equals("FOR_STATEMENT") ||
                 change.getChangedEntity().getLabel().equals("WHILE_STATEMENT") ||
                 change.getChangedEntity().getLabel().equals("DO_STATEMENT"))) {
            return true;
        }

        return false;
    }

    private boolean isComputationChange(SourceCodeChange change) {
        for(ComputationChangeTypes c : ComputationChangeTypes.values()) {
            if(c.name().equals(change.getChangeType().name())) {
                return true;
            }
        }

        if(change.getChangeType().name().startsWith("STATEMENT_") &&
                (change.getChangedEntity().getLabel().equals("ASSIGNMENT") ||
                 change.getChangedEntity().getLabel().equals("POSTFIX_EXPRESSION"))) {
            return true;
        }
        return false;
    }

    private boolean isOtherChange(SourceCodeChange change) {
        for(OtherChangeTypes c : OtherChangeTypes.values()) {
            if(c.name().equals(change.getChangeType().name())) {
                return true;
            }
        }
        return false;
    }
}
