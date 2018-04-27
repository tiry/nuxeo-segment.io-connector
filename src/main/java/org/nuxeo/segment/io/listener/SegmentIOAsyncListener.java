/*
 * (C) Copyright 2014 Nuxeo SA (http://nuxeo.com/) and others.
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
 *     Thierry Delprat
 */
package org.nuxeo.segment.io.listener;

import java.io.Serializable;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventBundle;
import org.nuxeo.ecm.core.event.PostCommitEventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.segment.io.SegmentIO;
import org.nuxeo.segment.io.SegmentIOMapper;

public class SegmentIOAsyncListener implements PostCommitEventListener {

    @Override
    public void handleEvent(EventBundle bundle) {

        SegmentIO service = Framework.getService(SegmentIO.class);

        List<String> eventToProcess = new ArrayList<>();

        for (String event : service.getMappedEvents()) {
            if (bundle.containsEventName(event)) {
                eventToProcess.add(event);
            }
        }

        if (eventToProcess.size() > 0) {
            Map<String, List<SegmentIOMapper>> event2Mappers = service.getMappers(eventToProcess);

            try {
                // Force system login in order to have access to user directory
                LoginContext login = Framework.login();
                try {
                    processEvents(event2Mappers, bundle);
                } finally {
                    if (login != null) {
                        login.logout();
                    }
                }
            } catch (LoginException e) {
                throw new NuxeoException(e);
            }
        }
    }

    protected void processEvents(Map<String, List<SegmentIOMapper>> event2Mappers, EventBundle bundle) {

        for (Event event : bundle) {
            List<SegmentIOMapper> mappers = event2Mappers.get(event.getName());
            if (mappers == null || mappers.size() == 0) {
                continue;
            }

            for (SegmentIOMapper mapper : mappers) {

                Map<String, Object> ctx = new HashMap<>();

                Principal princ = event.getContext().getPrincipal();
                NuxeoPrincipal principal;
                if (princ instanceof NuxeoPrincipal) {
                    principal = (NuxeoPrincipal) princ;
                } else {
                    principal = Framework.getService(UserManager.class).getPrincipal(princ.getName());
                }

                ctx.put("event", event);
                ctx.put("eventContext", event.getContext());
                ctx.put("principal", principal);
                if (event.getContext() instanceof DocumentEventContext) {
                    DocumentEventContext docCtx = (DocumentEventContext) event.getContext();
                    ctx.put("doc", docCtx.getSourceDocument());
                    ctx.put("repository", docCtx.getRepositoryName());
                    ctx.put("session", docCtx.getCoreSession());
                    ctx.put("dest", docCtx.getDestination());
                }
                Map<String, Serializable> mapped = mapper.getMappedData(ctx);

                if (mapper.isIdentify()) {
                    Framework.getService(SegmentIO.class).identify(principal, mapped);
                } else {
                    Framework.getService(SegmentIO.class).track(principal, event.getName(), mapped);
                }

            }
        }
    }

}
