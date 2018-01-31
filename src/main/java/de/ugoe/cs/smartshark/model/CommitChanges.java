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

package de.ugoe.cs.smartshark.model;

import java.util.Map;
import org.bson.types.ObjectId;
import org.mongodb.morphia.annotations.Entity;
import org.mongodb.morphia.annotations.Id;
import org.mongodb.morphia.annotations.Property;

/**
 * @author Fabian Trautsch
 */
@Entity(value = "commit_changes", noClassnameStored = true)
public class CommitChanges {
    @Id
    @Property("_id")
    private ObjectId id;

    @Property("old_commit_id")
    private ObjectId oldCommitId;

    @Property("new_commit_id")
    private ObjectId newCommitId;

    @Property("changes")
    private Map<String, Integer> changes;

    public CommitChanges() {

    }

    public CommitChanges(ObjectId oldCommitId, ObjectId newCommitId, Map<String, Integer> changes) {
        this.oldCommitId = oldCommitId;
        this.newCommitId = newCommitId;
        this.changes = changes;
    }

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public ObjectId getOldCommitId() {
        return oldCommitId;
    }

    public void setOldCommitId(ObjectId oldCommitId) {
        this.oldCommitId = oldCommitId;
    }

    public ObjectId getNewCommitId() {
        return newCommitId;
    }

    public void setNewCommitId(ObjectId newCommitId) {
        this.newCommitId = newCommitId;
    }

    public Map<String, Integer> getChanges() {
        return changes;
    }

    public void setChanges(Map<String, Integer> changes) {
        this.changes = changes;
    }
}
