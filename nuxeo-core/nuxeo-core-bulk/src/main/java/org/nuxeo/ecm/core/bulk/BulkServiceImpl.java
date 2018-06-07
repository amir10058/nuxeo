/*
 * (C) Copyright 2018 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *       Kevin Leturc <kleturc@nuxeo.com>
 */
package org.nuxeo.ecm.core.bulk;

import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.nuxeo.ecm.core.bulk.BulkStatus.State.SCHEDULED;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.repository.RepositoryManager;
import org.nuxeo.ecm.core.bulk.BulkStatus.State;
import org.nuxeo.lib.stream.computation.Record;
import org.nuxeo.lib.stream.log.LogManager;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.kv.KeyValueService;
import org.nuxeo.runtime.kv.KeyValueStore;
import org.nuxeo.runtime.stream.StreamService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Basic implementation of {@link BulkService}.
 *
 * @since 10.2
 */
public class BulkServiceImpl implements BulkService {

    private static final Log log = LogFactory.getLog(BulkServiceImpl.class);

    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    protected static final String SET_STREAM_NAME = "documentSet";

    protected static final String COMMAND = ":command";

    protected static final String CREATION_DATE = ":creationDate";

    protected static final String STATE = ":state";

    protected static final String SCROLLED_DOCUMENT_COUNT = ":scrolledDocumentCount";

    protected final BulkServiceDescriptor descriptor;

    /** Initialized in a lazy way. */
    protected KeyValueStore kvStore;

    /** Initialized in a lazy way. */
    protected LogManager logManager;

    public BulkServiceImpl(BulkServiceDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    @Override
    public BulkStatus runOperation(BulkCommand command) {
        if (log.isDebugEnabled()) {
            log.debug("Run operation with command=" + command);
        }
        // fill command object
        if (isEmpty(command.getRepository())) {
            String repository = Framework.getService(RepositoryManager.class).getDefaultRepositoryName();
            command.withRepository(repository);
        }
        // create the operation id and status
        UUID bulkOperationId = UUID.randomUUID();

        BulkStatus status = new BulkStatus();
        status.setUUID(bulkOperationId);
        status.setState(SCHEDULED);
        status.setCreationDate(ZonedDateTime.now());
        status.setCommand(command);

        try {
            byte[] commandAsBytes = OBJECT_MAPPER.writeValueAsBytes(command);

            // store the bulk command and status in the key/value store
            KeyValueStore keyValueStore = getKvStore();
            keyValueStore.put(bulkOperationId + STATE, status.getState().toString());
            keyValueStore.put(bulkOperationId + CREATION_DATE, status.getCreationDate().toString());
            keyValueStore.put(bulkOperationId + COMMAND, commandAsBytes);

            // send it to nuxeo-stream
            String key = bulkOperationId.toString();
            getLogManager().getAppender(SET_STREAM_NAME).append(key, new Record(key, commandAsBytes));
        } catch (JsonProcessingException e) {
            throw new NuxeoException("Unable to serialize the bulk command=" + command, e);
        }
        return status;
    }

    @Override
    public BulkStatus getStatus(UUID bulkOperationId) {
        String commandAsString = "";
        try {
            BulkStatus status = new BulkStatus();
            status.setUUID(bulkOperationId);

            // retrieve values from KeyValueStore
            KeyValueStore keyValueStore = getKvStore();
            String state = keyValueStore.getString(bulkOperationId + STATE);
            status.setState(State.valueOf(state));

            String creationDate = keyValueStore.getString(bulkOperationId + CREATION_DATE);
            status.setCreationDate(ZonedDateTime.parse(creationDate));

            commandAsString = keyValueStore.getString(bulkOperationId + COMMAND);
            BulkCommand command = OBJECT_MAPPER.readValue(commandAsString, BulkCommand.class);
            status.setCommand(command);

            Long scrolledDocumentCount = keyValueStore.getLong(bulkOperationId + SCROLLED_DOCUMENT_COUNT);
            status.setScrolledDocumentCount(scrolledDocumentCount);

            return status;
        } catch (IOException e) {
            throw new NuxeoException("Unable to deserialize the bulk command=" + commandAsString, e);
        }
    }

    public KeyValueStore getKvStore() {
        if (kvStore == null) {
            kvStore = Framework.getService(KeyValueService.class).getKeyValueStore(descriptor.kvStore);
        }
        return kvStore;
    }

    public LogManager getLogManager() {
        if (logManager == null) {
            logManager = Framework.getService(StreamService.class).getLogManager(descriptor.logConfig);
        }
        return logManager;
    }
}
