package io.lumify.web.routes.workspace;

import com.altamiracorp.bigtable.model.user.ModelUserContext;
import com.google.common.base.Joiner;
import com.google.inject.Inject;
import io.lumify.core.config.Configuration;
import io.lumify.core.exception.LumifyException;
import io.lumify.core.ingest.video.VideoFrameInfo;
import io.lumify.core.model.audit.Audit;
import io.lumify.core.model.audit.AuditAction;
import io.lumify.core.model.audit.AuditRepository;
import io.lumify.core.model.ontology.OntologyProperty;
import io.lumify.core.model.ontology.OntologyRepository;
import io.lumify.core.model.properties.LumifyProperties;
import io.lumify.core.model.properties.MediaLumifyProperties;
import io.lumify.core.model.termMention.TermMentionRepository;
import io.lumify.core.model.user.AuthorizationRepository;
import io.lumify.core.model.user.UserRepository;
import io.lumify.core.model.workspace.WorkspaceRepository;
import io.lumify.core.security.LumifyVisibility;
import io.lumify.core.security.VisibilityTranslator;
import io.lumify.core.user.User;
import io.lumify.core.util.GraphUtil;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import io.lumify.miniweb.HandlerChain;
import io.lumify.web.BaseRequestHandler;
import io.lumify.web.clientapi.model.*;
import org.securegraph.*;
import org.securegraph.mutation.ExistingElementMutation;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.securegraph.util.IterableUtils.count;
import static org.securegraph.util.IterableUtils.toList;

public class WorkspacePublish extends BaseRequestHandler {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(WorkspacePublish.class);
    private final TermMentionRepository termMentionRepository;
    private final AuditRepository auditRepository;
    private final UserRepository userRepository;
    private final OntologyRepository ontologyRepository;
    private final AuthorizationRepository authorizationRepository;
    private final Graph graph;
    private final VisibilityTranslator visibilityTranslator;
    private final String entityHasImageIri;

    @Inject
    public WorkspacePublish(
            final TermMentionRepository termMentionRepository,
            final AuditRepository auditRepository,
            final UserRepository userRepository,
            final Configuration configuration,
            final Graph graph,
            final VisibilityTranslator visibilityTranslator,
            final OntologyRepository ontologyRepository,
            final WorkspaceRepository workspaceRepository,
            final AuthorizationRepository authorizationRepository) {
        super(userRepository, workspaceRepository, configuration);
        this.termMentionRepository = termMentionRepository;
        this.auditRepository = auditRepository;
        this.graph = graph;
        this.visibilityTranslator = visibilityTranslator;
        this.userRepository = userRepository;
        this.ontologyRepository = ontologyRepository;
        this.authorizationRepository = authorizationRepository;

        this.entityHasImageIri = this.getConfiguration().get(Configuration.ONTOLOGY_IRI_ENTITY_HAS_IMAGE, null);
        if (this.entityHasImageIri == null) {
            throw new LumifyException("Could not find configuration for " + Configuration.ONTOLOGY_IRI_ENTITY_HAS_IMAGE);
        }
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, HandlerChain chain) throws Exception {
        String publishDataString = getRequiredParameter(request, "publishData");
        ClientApiPublishItem[] publishData = getObjectMapper().readValue(publishDataString, ClientApiPublishItem[].class);
        User user = getUser(request);
        Authorizations authorizations = getAuthorizations(request, user);
        String workspaceId = getActiveWorkspaceId(request);

        LOGGER.debug("publishing:\n%s", Joiner.on("\n").join(publishData));
        ClientApiWorkspacePublishResponse workspacePublishResponse = new ClientApiWorkspacePublishResponse();
        publishVertices(publishData, workspacePublishResponse, workspaceId, user, authorizations);
        publishEdges(publishData, workspacePublishResponse, workspaceId, user, authorizations);
        publishProperties(publishData, workspacePublishResponse, workspaceId, user, authorizations);

        LOGGER.debug("publishing results: %s", workspacePublishResponse);
        respondWithClientApiObject(response, workspacePublishResponse);
    }

    private void publishVertices(ClientApiPublishItem[] publishData, ClientApiWorkspacePublishResponse workspacePublishResponse, String workspaceId, User user, Authorizations authorizations) {
        LOGGER.debug("BEGIN publishVertices");
        for (ClientApiPublishItem data : publishData) {
            try {
                if (!(data instanceof ClientApiVertexPublishItem)) {
                    continue;
                }
                ClientApiVertexPublishItem vertexPublishItem = (ClientApiVertexPublishItem) data;
                String vertexId = vertexPublishItem.getVertexId();
                checkNotNull(vertexId);
                Vertex vertex = graph.getVertex(vertexId, authorizations);
                checkNotNull(vertex);
                if (GraphUtil.getSandboxStatus(vertex, workspaceId) == SandboxStatus.PUBLIC) {
                    String msg;
                    if (data.getAction() == ClientApiPublishItem.Action.delete) {
                        msg = "Cannot delete public vertex " + vertexId;
                    } else {
                        msg = "Vertex " + vertexId + " is already public";
                    }
                    LOGGER.warn(msg);
                    data.setErrorMessage(msg);
                    workspacePublishResponse.addFailure(data);
                    continue;
                }
                publishVertex(vertex, data.getAction(), authorizations, workspaceId, user);
            } catch (Exception ex) {
                LOGGER.error("Error publishing %s", data.toString(), ex);
                data.setErrorMessage(ex.getMessage());
                workspacePublishResponse.addFailure(data);
            }
        }
        LOGGER.debug("END publishVertices");
        graph.flush();
    }

    private void publishEdges(ClientApiPublishItem[] publishData, ClientApiWorkspacePublishResponse workspacePublishResponse, String workspaceId, User user, Authorizations authorizations) {
        LOGGER.debug("BEGIN publishEdges");
        for (ClientApiPublishItem data : publishData) {
            try {
                if (!(data instanceof ClientApiRelationshipPublishItem)) {
                    continue;
                }
                ClientApiRelationshipPublishItem relationshipPublishItem = (ClientApiRelationshipPublishItem) data;
                Edge edge = graph.getEdge(relationshipPublishItem.getEdgeId(), authorizations);
                Vertex sourceVertex = edge.getVertex(Direction.OUT, authorizations);
                Vertex destVertex = edge.getVertex(Direction.IN, authorizations);
                if (GraphUtil.getSandboxStatus(edge, workspaceId) == SandboxStatus.PUBLIC) {
                    String error_msg;
                    if (data.getAction() == ClientApiPublishItem.Action.delete) {
                        error_msg = "Cannot delete a public edge";
                    } else {
                        error_msg = "Edge is already public";
                    }
                    LOGGER.warn(error_msg);
                    data.setErrorMessage(error_msg);
                    workspacePublishResponse.addFailure(data);
                    continue;
                }

                if (sourceVertex != null && destVertex != null && GraphUtil.getSandboxStatus(sourceVertex, workspaceId) != SandboxStatus.PUBLIC &&
                        GraphUtil.getSandboxStatus(destVertex, workspaceId) != SandboxStatus.PUBLIC) {
                    String error_msg = "Cannot publish edge, " + edge.getId() + ", because either source and/or dest vertex are not public";
                    LOGGER.warn(error_msg);
                    data.setErrorMessage(error_msg);
                    workspacePublishResponse.addFailure(data);
                    continue;
                }
                publishEdge(edge, sourceVertex, destVertex, data.getAction(), workspaceId, user, authorizations);
            } catch (Exception ex) {
                LOGGER.error("Error publishing %s", data.toString(), ex);
                data.setErrorMessage(ex.getMessage());
                workspacePublishResponse.addFailure(data);
            }
        }
        LOGGER.debug("END publishEdges");
        graph.flush();
    }

    private void publishProperties(ClientApiPublishItem[] publishData, ClientApiWorkspacePublishResponse workspacePublishResponse, String workspaceId, User user, Authorizations authorizations) {
        LOGGER.debug("BEGIN publishProperties");
        for (ClientApiPublishItem data : publishData) {
            try {
                if (!(data instanceof ClientApiPropertyPublishItem)) {
                    continue;
                }
                ClientApiPropertyPublishItem propertyPublishItem = (ClientApiPropertyPublishItem) data;
                Element element = getPropertyElement(authorizations, propertyPublishItem);

                String propertyKey = propertyPublishItem.getKey();
                String propertyName = propertyPublishItem.getName();
                String propertyVisibilityString = propertyPublishItem.getVisibilityString();

                OntologyProperty ontologyProperty = ontologyRepository.getPropertyByIRI(propertyName);
                checkNotNull(ontologyProperty, "Could not find ontology property: " + propertyName);
                if (!ontologyProperty.getUserVisible() || propertyName.equals(LumifyProperties.ENTITY_IMAGE_VERTEX_ID.getPropertyName())) {
                    continue;
                }

                List<Property> properties = toList(element.getProperties(propertyKey, propertyName));
                SandboxStatus[] sandboxStatuses = GraphUtil.getPropertySandboxStatuses(properties, workspaceId);
                boolean propertyFailed = false;
                for (int propertyIndex = 0; propertyIndex < properties.size(); propertyIndex++) {
                    Property property = properties.get(propertyIndex);
                    if (propertyVisibilityString != null &&
                            !property.getVisibility().getVisibilityString().equals(propertyVisibilityString)) {
                        continue;
                    }
                    SandboxStatus propertySandboxStatus = sandboxStatuses[propertyIndex];

                    if (propertySandboxStatus == SandboxStatus.PUBLIC) {
                        String error_msg;
                        if (data.getAction() == ClientApiPublishItem.Action.delete) {
                            error_msg = "Cannot delete a public property";
                        } else {
                            error_msg = "Property is already public";
                        }
                        LOGGER.warn(error_msg);
                        data.setErrorMessage(error_msg);
                        workspacePublishResponse.addFailure(data);
                        propertyFailed = true;
                    }
                }

                if (propertyFailed) {
                    continue;
                }

                if (GraphUtil.getSandboxStatus(element, workspaceId) != SandboxStatus.PUBLIC) {
                    String errorMessage = "Cannot publish a modification of a property on a private element: " + element.getId();
                    VisibilityJson visibilityJson = LumifyProperties.VISIBILITY_JSON.getPropertyValue(element);
                    LOGGER.warn("%s: visibilityJson: %s, workspaceId: %s", errorMessage, visibilityJson.toString(), workspaceId);
                    data.setErrorMessage(errorMessage);
                    workspacePublishResponse.addFailure(data);
                    continue;
                }

                publishProperty(element, data.getAction(), propertyKey, propertyName, workspaceId, user, authorizations);
            } catch (Exception ex) {
                LOGGER.error("Error publishing %s", data.toString(), ex);
                data.setErrorMessage(ex.getMessage());
                workspacePublishResponse.addFailure(data);
            }
        }
        LOGGER.debug("END publishProperties");
        graph.flush();
    }

    private Element getPropertyElement(Authorizations authorizations, ClientApiPropertyPublishItem data) {
        Element element = null;

        String elementId = data.getEdgeId();
        if (elementId != null) {
            element = graph.getEdge(elementId, authorizations);
        }

        if (element == null) {
            elementId = data.getVertexId();
            if (elementId != null) {
                element = graph.getVertex(elementId, authorizations);
            }
        }

        if (element == null) {
            elementId = data.getElementId();
            checkNotNull(elementId, "elementId, vertexId, or edgeId is required to publish a property");
            element = graph.getVertex(elementId, authorizations);
            if (element == null) {
                element = graph.getEdge(elementId, authorizations);
            }
        }

        checkNotNull(element, "Could not find edge/vertex with id: " + elementId);
        return element;
    }

    private void publishVertex(Vertex vertex, ClientApiPublishItem.Action action, Authorizations authorizations, String workspaceId, User user) throws IOException {
        if (action == ClientApiPublishItem.Action.delete) {
            graph.removeVertex(vertex, authorizations);
            return;
        }

        // Need to elevate with videoFrame auth to be able to publish VideoFrame properties
        Authorizations authWithVideoFrame = authorizationRepository.createAuthorizations(authorizations, VideoFrameInfo.VISIBILITY);
        vertex = graph.getVertex(vertex.getId(), authWithVideoFrame);

        LOGGER.debug("publishing vertex %s(%s)", vertex.getId(), vertex.getVisibility().toString());
        Visibility originalVertexVisibility = vertex.getVisibility();
        Property visibilityJsonProperty = LumifyProperties.VISIBILITY_JSON.getProperty(vertex);
        VisibilityJson visibilityJson = LumifyProperties.VISIBILITY_JSON.getPropertyValue(vertex);

        if (!visibilityJson.getWorkspaces().contains(workspaceId)) {
            throw new LumifyException(String.format("vertex with id '%s' is not local to workspace '%s'", vertex.getId(), workspaceId));
        }

        visibilityJson = GraphUtil.updateVisibilityJsonRemoveFromAllWorkspace(visibilityJson);
        LumifyVisibility lumifyVisibility = visibilityTranslator.toVisibility(visibilityJson);

        ExistingElementMutation<Vertex> vertexElementMutation = vertex.prepareMutation();
        vertexElementMutation.alterElementVisibility(lumifyVisibility.getVisibility());

        for (Property property : vertex.getProperties()) {
            OntologyProperty ontologyProperty = ontologyRepository.getPropertyByIRI(property.getName());
            checkNotNull(ontologyProperty, "Could not find ontology property " + property.getName());
            if (!ontologyProperty.getUserVisible() && !property.getName().equals(LumifyProperties.ENTITY_IMAGE_VERTEX_ID.getPropertyName())) {
                publishProperty(vertexElementMutation, property, workspaceId, user);
            }
        }

        Map<String, Object> metadata = new HashMap<String, Object>();
        // we need to alter the visibility of the json property, otherwise we'll have two json properties, one with the old visibility and one with the new.
        LumifyProperties.VISIBILITY_JSON.alterVisibility(vertexElementMutation, visibilityJsonProperty.getKey(), lumifyVisibility.getVisibility());
        LumifyProperties.VISIBILITY_JSON.setMetadata(metadata, visibilityJson);
        LumifyProperties.VISIBILITY_JSON.addPropertyValue(vertexElementMutation, visibilityJsonProperty.getKey(), visibilityJson, metadata, lumifyVisibility.getVisibility());
        vertexElementMutation.save(authorizations);

        auditRepository.auditVertex(AuditAction.PUBLISH, vertex.getId(), "", "", user, lumifyVisibility.getVisibility());

        ModelUserContext systemModelUser = userRepository.getModelUserContext(authorizations, LumifyVisibility.SUPER_USER_VISIBILITY_STRING);
        for (Audit row : auditRepository.findByRowStartsWith(vertex.getId(), systemModelUser)) {
            auditRepository.updateColumnVisibility(row, originalVertexVisibility, lumifyVisibility.getVisibility().getVisibilityString());
        }

        for (Vertex termMention : termMentionRepository.findBySourceGraphVertex(vertex.getId(), authorizations)) {
            if (count(termMention.getEdgeIds(Direction.OUT, LumifyProperties.TERM_MENTION_LABEL_RESOLVED_TO, authorizations)) > 0) {
                continue; // skip all resolved terms. They will be published by the edge.
            }
            termMentionRepository.updateVisibility(termMention, lumifyVisibility.getVisibility(), authorizations);
        }

        graph.flush();
    }

    private void publishProperty(Element element, ClientApiPublishItem.Action action, String key, String name, String workspaceId, User user, Authorizations authorizations) {
        if (action == ClientApiPublishItem.Action.delete) {
            element.removeProperty(key, name, authorizations);
            return;
        }
        ExistingElementMutation elementMutation = element.prepareMutation();
        Iterable<Property> properties = element.getProperties(name);
        for (Property property : properties) {
            if (!property.getKey().equals(key)) {
                continue;
            }
            if (publishProperty(elementMutation, property, workspaceId, user)) {
                elementMutation.save(authorizations);
                graph.flush();
                return;
            }
        }
        throw new LumifyException(String.format("no property with key '%s' and name '%s' found on workspace '%s'", key, name, workspaceId));
    }

    private boolean publishProperty(ExistingElementMutation elementMutation, Property property, String workspaceId, User user) {
        VisibilityJson visibilityJson = LumifyProperties.VISIBILITY_JSON.getMetadataValue(property.getMetadata());
        if (visibilityJson == null) {
            LOGGER.debug("skipping property %s. no visibility json property", property.toString());
            return false;
        }
        if (!visibilityJson.getWorkspaces().contains(workspaceId)) {
            LOGGER.debug("skipping property %s. doesn't have workspace in json.", property.toString());
            return false;
        }

        LOGGER.debug("publishing property %s:%s(%s)", property.getKey(), property.getName(), property.getVisibility().toString());
        visibilityJson = GraphUtil.updateVisibilityJsonRemoveFromAllWorkspace(visibilityJson);
        LumifyVisibility lumifyVisibility = visibilityTranslator.toVisibility(visibilityJson);

        elementMutation
                .alterPropertyVisibility(property, lumifyVisibility.getVisibility())
                .alterPropertyMetadata(property, LumifyProperties.VISIBILITY_JSON.getPropertyName(), visibilityJson.toString());

        auditRepository.auditEntityProperty(AuditAction.PUBLISH, elementMutation.getElement().getId(), property.getKey(),
                property.getName(), property.getValue(), property.getValue(), "", "", property.getMetadata(), user, lumifyVisibility.getVisibility());
        return true;
    }

    private void publishEdge(Edge edge, Vertex sourceVertex, Vertex destVertex, ClientApiPublishItem.Action action, String workspaceId, User user, Authorizations authorizations) {
        if (action == ClientApiPublishItem.Action.delete) {
            graph.removeEdge(edge, authorizations);
            return;
        }

        LOGGER.debug("publishing edge %s(%s)", edge.getId(), edge.getVisibility().toString());
        VisibilityJson visibilityJson = LumifyProperties.VISIBILITY_JSON.getPropertyValue(edge);
        if (!visibilityJson.getWorkspaces().contains(workspaceId)) {
            throw new LumifyException(String.format("edge with id '%s' is not local to workspace '%s'", edge.getId(), workspaceId));
        }

        if (edge.getLabel().equals(entityHasImageIri)) {
            publishGlyphIconProperty(edge, workspaceId, user, authorizations);
        }

        edge.removeProperty(LumifyProperties.VISIBILITY_JSON.getPropertyName(), authorizations);
        visibilityJson = GraphUtil.updateVisibilityJsonRemoveFromAllWorkspace(visibilityJson);
        LumifyVisibility lumifyVisibility = visibilityTranslator.toVisibility(visibilityJson);
        ExistingElementMutation<Edge> edgeExistingElementMutation = edge.prepareMutation();
        Visibility originalEdgeVisibility = edge.getVisibility();
        edgeExistingElementMutation.alterElementVisibility(lumifyVisibility.getVisibility());

        for (Property property : edge.getProperties()) {
            OntologyProperty ontologyProperty = ontologyRepository.getPropertyByIRI(property.getName());
            checkNotNull(ontologyProperty, "Could not find ontology property " + property.getName());
            if (!ontologyProperty.getUserVisible() && !property.getName().equals(LumifyProperties.ENTITY_IMAGE_VERTEX_ID.getPropertyName())) {
                publishProperty(edgeExistingElementMutation, property, workspaceId, user);
            }
        }

        auditRepository.auditEdgeElementMutation(AuditAction.PUBLISH, edgeExistingElementMutation, edge, sourceVertex, destVertex, "", user, lumifyVisibility.getVisibility());

        Map<String, Object> metadata = new HashMap<String, Object>();
        LumifyProperties.VISIBILITY_JSON.setMetadata(metadata, visibilityJson);
        LumifyProperties.VISIBILITY_JSON.setProperty(edgeExistingElementMutation, visibilityJson, metadata, lumifyVisibility.getVisibility());
        edge = edgeExistingElementMutation.save(authorizations);

        auditRepository.auditRelationship(AuditAction.PUBLISH, sourceVertex, destVertex, edge, "", "", user, edge.getVisibility());

        ModelUserContext systemUser = userRepository.getModelUserContext(authorizations, LumifyVisibility.SUPER_USER_VISIBILITY_STRING);
        for (Audit row : auditRepository.findByRowStartsWith(edge.getId(), systemUser)) {
            auditRepository.updateColumnVisibility(row, originalEdgeVisibility, lumifyVisibility.getVisibility().getVisibilityString());
        }

        for (Vertex termMention : termMentionRepository.findResolvedTo(destVertex.getId(), authorizations)) {
            termMentionRepository.updateVisibility(termMention, lumifyVisibility.getVisibility(), authorizations);
        }
    }

    private void publishGlyphIconProperty(Edge hasImageEdge, String workspaceId, User user, Authorizations authorizations) {
        Vertex entityVertex = hasImageEdge.getVertex(Direction.OUT, authorizations);
        checkNotNull(entityVertex, "Could not find has image source vertex " + hasImageEdge.getVertexId(Direction.OUT));
        ExistingElementMutation elementMutation = entityVertex.prepareMutation();
        Iterable<Property> glyphIconProperties = entityVertex.getProperties(LumifyProperties.ENTITY_IMAGE_VERTEX_ID.getPropertyName());
        for (Property glyphIconProperty : glyphIconProperties) {
            if (publishProperty(elementMutation, glyphIconProperty, workspaceId, user)) {
                elementMutation.save(authorizations);
                return;
            }
        }
        LOGGER.warn("new has image edge without a glyph icon property being set on vertex %s", entityVertex.getId());
    }
}
