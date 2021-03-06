/*
 * (C) Copyright 2006-2015 Nuxeo SA (http://nuxeo.com/) and others.
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
 *     Bogdan Stefanescu
 */
package org.nuxeo.ecm.automation.jaxrs.io.documents;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.JsonEncoding;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonGenerator;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;
import org.nuxeo.common.utils.StringUtils;
import org.nuxeo.ecm.automation.core.util.DateTimeFormat;
import org.nuxeo.ecm.automation.core.util.JSONPropertyWriter;
import org.nuxeo.ecm.automation.io.services.enricher.ContentEnricherService;
import org.nuxeo.ecm.automation.io.services.enricher.HeaderDocEvaluationContext;
import org.nuxeo.ecm.automation.io.services.enricher.RestEvaluationContext;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.Lock;
import org.nuxeo.ecm.core.api.PropertyException;
import org.nuxeo.ecm.core.api.model.DocumentPart;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.api.model.PropertyNotFoundException;
import org.nuxeo.ecm.core.io.download.DownloadService;
import org.nuxeo.ecm.core.io.marshallers.json.document.DocumentModelJsonWriter;
import org.nuxeo.ecm.core.io.registry.MarshallerHelper;
import org.nuxeo.ecm.core.io.registry.MarshallerRegistry;
import org.nuxeo.ecm.core.schema.utils.DateParser;
import org.nuxeo.ecm.platform.web.common.vh.VirtualHostHelper;
import org.nuxeo.ecm.webengine.jaxrs.coreiodelegate.JsonCoreIODelegate;
import org.nuxeo.runtime.api.Framework;

/**
 * @deprecated this JAX-RS marshaller was migrated to {@link DocumentModelJsonWriter}. To use it in JAX-RS, use
 *             {@link JsonCoreIODelegate} to forward JAX-RS marshalling to core io. To use it your code, please refer to
 *             {@link MarshallerRegistry} service or use {@link MarshallerHelper}.
 */
@Deprecated
@Provider
@Produces({ "application/json+nxentity", "application/json" })
public class JsonDocumentWriter implements MessageBodyWriter<DocumentModel> {

    public static final String DOCUMENT_PROPERTIES_HEADER = "X-NXDocumentProperties";

    private static final Log log = LogFactory.getLog(JsonDocumentWriter.class);

    @Context
    JsonFactory factory;

    @Context
    protected HttpHeaders headers;

    @Context
    private HttpServletRequest servletRequest;

    @Override
    public long getSize(DocumentModel arg0, Class<?> arg1, Type arg2, Annotation[] arg3, MediaType arg4) {
        return -1L;
    }

    @Override
    public boolean isWriteable(Class<?> arg0, Type arg1, Annotation[] arg2, MediaType arg3) {
        return DocumentModel.class.isAssignableFrom(arg0);
    }

    @Override
    public void writeTo(DocumentModel doc, Class<?> arg1, Type arg2, Annotation[] arg3, MediaType arg4,
            MultivaluedMap<String, Object> arg5, OutputStream out) throws IOException, WebApplicationException {
        try {
            // schema names: dublincore, file, ... or *
            List<String> props = headers.getRequestHeader(DOCUMENT_PROPERTIES_HEADER);
            String[] schemas = null;
            if (props != null && !props.isEmpty()) {
                schemas = StringUtils.split(props.get(0), ',', true);
            }
            writeDocument(out, doc, schemas);
        } catch (IOException e) {
            log.error("Failed to serialize document", e);
            throw e;
        }
    }

    public void writeDocument(OutputStream out, DocumentModel doc, String[] schemas) throws IOException {
        writeDocument(out, doc, schemas, null);
    }

    /**
     * @since 5.6
     */
    public void writeDocument(OutputStream out, DocumentModel doc, String[] schemas,
            Map<String, String> contextParameters) throws IOException {
        writeDocument(factory.createJsonGenerator(out, JsonEncoding.UTF8), doc, schemas, contextParameters, headers,
                servletRequest);
    }

    public static void writeDocument(JsonGenerator jg, DocumentModel doc, String[] schemas, ServletRequest request)
            throws IOException {
        writeDocument(jg, doc, schemas, null, request);
    }

    /**
     * @since 5.6
     */
    public static void writeDocument(JsonGenerator jg, DocumentModel doc, String[] schemas,
            Map<String, String> contextParameters, ServletRequest request) throws IOException {
        writeDocument(jg, doc, schemas, contextParameters, null, request);
    }

    /**
     * @param jg a ready to user JSON generator
     * @param doc the document to serialize
     * @param schemas an array of schemas that must be serialized in the properties map
     * @param contextParameters
     * @param headers
     * @param request the ServletRequest. If null blob URLs won't be generated.
     * @since 5.7.3
     */
    public static void writeDocument(JsonGenerator jg, DocumentModel doc, String[] schemas,
            Map<String, String> contextParameters, HttpHeaders headers, ServletRequest request) throws IOException {
        jg.writeStartObject();
        jg.writeStringField("entity-type", "document");
        jg.writeStringField("repository", doc.getRepositoryName());
        jg.writeStringField("uid", doc.getId());
        jg.writeStringField("path", doc.getPathAsString());
        jg.writeStringField("type", doc.getType());
        jg.writeStringField("state", doc.getRef() != null ? doc.getCurrentLifeCycleState() : null);
        jg.writeStringField("parentRef", doc.getParentRef() != null ? doc.getParentRef().toString() : null);
        jg.writeStringField("versionLabel", doc.getVersionLabel());
        jg.writeBooleanField("isCheckedOut", doc.isCheckedOut());
        Lock lock = doc.getLockInfo();
        if (lock != null) {
            jg.writeStringField("lockOwner", lock.getOwner());
            jg.writeStringField("lockCreated", ISODateTimeFormat.dateTime().print(new DateTime(lock.getCreated())));
        }
        jg.writeStringField("title", doc.getTitle());
        try {
            Calendar cal = (Calendar) doc.getPropertyValue("dc:modified");
            if (cal != null) {
                jg.writeStringField("lastModified", DateParser.formatW3CDateTime(cal.getTime()));
            }
        } catch (PropertyNotFoundException e) {
            // ignore
        }

        if (schemas != null && schemas.length > 0) {
            jg.writeObjectFieldStart("properties");
            if (schemas.length == 1 && "*".equals(schemas[0])) {
                // full document
                for (String schema : doc.getSchemas()) {
                    writeProperties(jg, doc, schema, request);
                }
            } else {
                for (String schema : schemas) {
                    writeProperties(jg, doc, schema, request);
                }
            }
            jg.writeEndObject();
        }

        jg.writeArrayFieldStart("facets");
        for (String facet : doc.getFacets()) {
            jg.writeString(facet);
        }
        jg.writeEndArray();
        jg.writeStringField("changeToken", doc.getChangeToken());

        jg.writeObjectFieldStart("contextParameters");
        if (contextParameters != null && !contextParameters.isEmpty()) {
            for (Map.Entry<String, String> parameter : contextParameters.entrySet()) {
                jg.writeStringField(parameter.getKey(), parameter.getValue());
            }
        }

        writeRestContributions(jg, doc, headers, request);
        jg.writeEndObject();

        jg.writeEndObject();
        jg.flush();
    }

    /**
     * @param jg
     * @param doc
     * @param headers
     * @throws IOException
     * @throws JsonGenerationException
     * @since 5.7.3
     */
    protected static void writeRestContributions(JsonGenerator jg, DocumentModel doc, HttpHeaders headers,
            ServletRequest request) throws JsonGenerationException, IOException {
        ContentEnricherService rcs = Framework.getLocalService(ContentEnricherService.class);
        RestEvaluationContext ec = new HeaderDocEvaluationContext(doc, headers, request);
        rcs.writeContext(jg, ec);
    }

    protected static void writeProperties(JsonGenerator jg, DocumentModel doc, String schema, ServletRequest request)
            throws IOException {
        DocumentPart part = doc.getPart(schema);
        if (part == null) {
            return;
        }
        String prefix = part.getSchema().getNamespace().prefix;
        if (prefix == null || prefix.length() == 0) {
            prefix = schema;
        }
        prefix = prefix + ":";

        String blobUrlPrefix = null;
        if (request != null) {
            DownloadService downloadService = Framework.getService(DownloadService.class);
            blobUrlPrefix = VirtualHostHelper.getBaseURL(request) + downloadService.getDownloadUrl(doc, null, null)
                    + "/";
        }

        for (Property p : part.getChildren()) {
            jg.writeFieldName(prefix + p.getField().getName().getLocalName());
            writePropertyValue(jg, p, blobUrlPrefix);
        }
    }

    /**
     * Converts the value of the given core property to JSON format. The given filesBaseUrl is the baseUrl that can be
     * used to locate blob content and is useful to generate blob urls.
     */
    public static void writePropertyValue(JsonGenerator jg, Property prop, String filesBaseUrl)
            throws PropertyException, JsonGenerationException, IOException {
        JSONPropertyWriter.writePropertyValue(jg, prop, DateTimeFormat.W3C, filesBaseUrl);
    }

}
