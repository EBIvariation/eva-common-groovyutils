/*
 * Copyright 2022 EMBL - European Bioinformatics Institute
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.ac.ebi.eva.groovy.commons

import com.mongodb.ClientSessionOptions
import com.mongodb.MongoClientException
import com.mongodb.session.ClientSession
import org.bson.BsonDocument
import org.springframework.data.mongodb.core.MongoOperations
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.CriteriaDefinition

class RetryableBatchingCursor<T> implements Iterable<T> {
    CriteriaDefinition filterCriteria
    MongoTemplate mongoTemplate
    String collectionName
    Class<T> collectionClass
    int batchSize
    RetryableBatchingCursorIterator<T> resultIterator
    boolean hasSessionSupport = true
    boolean initialized = false

    // Need this to satisfy Spring gods who feast on empty constructors
    RetryableBatchingCursor() {}

    /***
     * A batching retryable cursor on top of MongoCursor that returns batches of results instead of single entities
     * @param filterCriteria Criteria to filter results for a given collectionClass (ex: where("rs").exists(false))
     * @param mongoTemplate MongoTemplate to use for querying
     * @param collectionClass Entity Class that should be used to return results (ex: SubmittedVariantEntity.class)
     * @param batchSize Optional: Number of results to return in each batch (default: 1000)
     * @param collectionName Optional: Name of specific collection to be queried. Supply this only if the entity
     * supplied in collectionClass does not have a corresponding "pre-bound" collection in the entity definition.
     * (ex: use submittedVariantEntity_custom if you wish to read SubmittedVariantEntity objects from a collection
     * named submittedVariantEntity_custom).
     * Note that this parameter is not needed if the collection name is submittedVariantEntity since that collection name is
     * already "pre-bound" to the SubmittedVariantEntity class in the entity definition.
     */
    RetryableBatchingCursor(CriteriaDefinition filterCriteria, MongoTemplate mongoTemplate, Class<T> collectionClass,
                            int batchSize = 1000, String collectionName = null) {
        this.filterCriteria = filterCriteria
        this.mongoTemplate = mongoTemplate
        this.collectionClass = collectionClass
        this.batchSize = batchSize
        this.collectionName = Objects.isNull(collectionName)? this.mongoTemplate.getCollectionName(this.collectionClass): collectionName
    }

    @Override
    RetryableBatchingCursorIterator<T> iterator() {
        if (!this.initialized) {
            ClientSessionOptions sessionOptions = ClientSessionOptions.builder()
                    .causallyConsistent(true).build()
            ClientSession session = null
            try {
                this.mongoTemplate.mongoDbFactory.getSession(sessionOptions)
                this.hasSessionSupport = true
            }
            catch (MongoClientException ex) { // Handle stand-alone instances that don't have session support
                if (ex.getMessage().toLowerCase().contains("sessions are not supported")) {
                    this.hasSessionSupport = false
                }
            }
            if (this.hasSessionSupport) {
                mongoTemplate.withSession(() -> session).execute { mongoOp ->
                    def serverSessionID = session.serverSession.identifier
                    this.initializeCursor(session, serverSessionID, mongoOp)}
            } else {
                this.initializeCursor(null, null, this.mongoTemplate)
            }
            this.initialized = true
        }
        return this.resultIterator
    }

    void initializeCursor(ClientSession session, BsonDocument serverSessionID, MongoOperations mongoOp) {
        def mongoIterator = mongoOp.getCollection(this.collectionName).find(
                this.filterCriteria.criteriaObject).noCursorTimeout(true).batchSize(batchSize).iterator()
        this.resultIterator = new RetryableBatchingCursorIterator(session, serverSessionID,
                this.collectionClass, this.mongoTemplate, mongoIterator, this.batchSize)
    }

    void setRefreshInterval (Long refreshInterval) {
        this.resultIterator.setRefreshInterval(refreshInterval)
    }
}
