/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.mapper;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.opensearch.common.ValidationException;
import org.opensearch.knn.common.KNNConstants;

import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.search.DocValuesFieldExistsQuery;
import org.apache.lucene.search.Query;
import org.opensearch.common.Explicit;
import org.opensearch.common.xcontent.ToXContent;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentParser;
import org.opensearch.common.xcontent.support.XContentMapValues;
import org.opensearch.index.fielddata.IndexFieldData;
import org.opensearch.index.mapper.FieldMapper;
import org.opensearch.index.mapper.MappedFieldType;
import org.opensearch.index.mapper.Mapper;
import org.opensearch.index.mapper.MapperParsingException;
import org.opensearch.index.mapper.ParametrizedFieldMapper;
import org.opensearch.index.mapper.ParseContext;
import org.opensearch.index.mapper.TextSearchInfo;
import org.opensearch.index.mapper.ValueFetcher;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.index.query.QueryShardException;
import org.opensearch.knn.index.KNNMethodContext;
import org.opensearch.knn.index.KNNSettings;
import org.opensearch.knn.index.KNNVectorIndexFieldData;
import org.opensearch.knn.index.VectorField;
import org.opensearch.knn.index.util.KNNEngine;
import org.opensearch.knn.indices.ModelDao;
import org.opensearch.search.aggregations.support.CoreValuesSourceType;
import org.opensearch.search.lookup.SearchLookup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.opensearch.knn.common.KNNConstants.KNN_METHOD;

/**
 * Field Mapper for KNN vector type.
 *
 * Extends ParametrizedFieldMapper in order to easily configure mapping parameters.
 *
 * Implementations of this class define what needs to be stored in Lucene's fieldType. This allows us to have
 * alternative mappings for the same field type.
 */
@Log4j2
public abstract class KNNVectorFieldMapper extends ParametrizedFieldMapper {

    public static final String CONTENT_TYPE = "knn_vector";
    public static final String KNN_FIELD = "knn_field";

    private static KNNVectorFieldMapper toType(FieldMapper in) {
        return (KNNVectorFieldMapper) in;
    }

    /**
     * Builder for KNNVectorFieldMapper. This class defines the set of parameters that can be applied to the knn_vector
     * field type
     */
    public static class Builder extends ParametrizedFieldMapper.Builder {
        protected Boolean ignoreMalformed;

        protected final Parameter<Boolean> stored = Parameter.boolParam("store", false, m -> toType(m).stored, false);
        protected final Parameter<Boolean> hasDocValues = Parameter.boolParam("doc_values", false, m -> toType(m).hasDocValues, true);
        protected final Parameter<Integer> dimension = new Parameter<>(KNNConstants.DIMENSION, false, () -> -1, (n, c, o) -> {
            if (o == null) {
                throw new IllegalArgumentException("Dimension cannot be null");
            }
            int value;
            try {
                value = XContentMapValues.nodeIntegerValue(o);
            } catch (Exception exception) {
                throw new IllegalArgumentException(
                    String.format("Unable to parse [dimension] from provided value [%s] for vector [%s]", o, name)
                );
            }
            if (value <= 0) {
                throw new IllegalArgumentException(String.format("Dimension value must be greater than 0 for vector: %s", name));
            }
            return value;
        }, m -> toType(m).dimension);

        /**
         * modelId provides a way for a user to generate the underlying library indices from an already serialized
         * model template index. If this parameter is set, it will take precedence. This parameter is only relevant for
         * library indices that require training.
         */
        protected final Parameter<String> modelId = Parameter.stringParam(KNNConstants.MODEL_ID, false, m -> toType(m).modelId, null);

        /**
         * knnMethodContext parameter allows a user to define their k-NN library index configuration. Defaults to an L2
         * hnsw default engine index without any parameters set
         */
        protected final Parameter<KNNMethodContext> knnMethodContext = new Parameter<>(
            KNN_METHOD,
            false,
            () -> null,
            (n, c, o) -> KNNMethodContext.parse(o),
            m -> toType(m).knnMethod
        ).setSerializer(((b, n, v) -> {
            b.startObject(n);
            v.toXContent(b, ToXContent.EMPTY_PARAMS);
            b.endObject();
        }), m -> m.getMethodComponent().getName()).setValidator(v -> {
            if (v == null) return;

            ValidationException validationException = null;
            if (v.isTrainingRequired()) {
                validationException = new ValidationException();
                validationException.addValidationError(String.format("\"%s\" requires training.", KNN_METHOD));
            }

            ValidationException methodValidation = v.validate();
            if (methodValidation != null) {
                validationException = validationException == null ? new ValidationException() : validationException;
                validationException.addValidationErrors(methodValidation.validationErrors());
            }

            if (validationException != null) {
                throw validationException;
            }
        });

        protected final Parameter<Map<String, String>> meta = Parameter.metaParam();

        protected String spaceType;
        protected String m;
        protected String efConstruction;

        protected ModelDao modelDao;

        public Builder(String name, ModelDao modelDao) {
            super(name);
            this.modelDao = modelDao;
        }

        /**
         * This constructor is for legacy purposes.
         * Checkout <a href="https://github.com/opendistro-for-elasticsearch/k-NN/issues/288">ODFE PR 288</a>
         *
         * @param name field name
         * @param spaceType Spacetype of field
         * @param m m value of field
         * @param efConstruction efConstruction value of field
         */
        public Builder(String name, String spaceType, String m, String efConstruction) {
            super(name);
            this.spaceType = spaceType;
            this.m = m;
            this.efConstruction = efConstruction;
        }

        @Override
        protected List<Parameter<?>> getParameters() {
            return Arrays.asList(stored, hasDocValues, dimension, meta, knnMethodContext, modelId);
        }

        protected Explicit<Boolean> ignoreMalformed(BuilderContext context) {
            if (ignoreMalformed != null) {
                return new Explicit<>(ignoreMalformed, true);
            }
            if (context.indexSettings() != null) {
                return new Explicit<>(IGNORE_MALFORMED_SETTING.get(context.indexSettings()), false);
            }
            return KNNVectorFieldMapper.Defaults.IGNORE_MALFORMED;
        }

        @Override
        public KNNVectorFieldMapper build(BuilderContext context) {
            // Originally, a user would use index settings to set the spaceType, efConstruction and m hnsw
            // parameters. Upon further review, it makes sense to set these parameters in the mapping of a
            // particular field. However, because users migrating from older versions will still use the index
            // settings to set these parameters, we will need to provide backwards compatibilty. In order to
            // handle this, we first check if the mapping is set, and, if so use it. If not, we check if the model is
            // set. If not, we fall back to the parameters set in the index settings. This means that if a user sets
            // the mappings, setting the index settings will have no impact.

            final KNNMethodContext knnMethodContext = this.knnMethodContext.getValue();
            validateMaxDimensions(knnMethodContext);
            final MultiFields multiFieldsBuilder = this.multiFieldsBuilder.build(this, context);
            final CopyTo copyToBuilder = copyTo.build();
            final Explicit<Boolean> ignoreMalformed = ignoreMalformed(context);
            final Map<String, String> metaValue = meta.getValue();
            if (knnMethodContext != null) {
                final KNNVectorFieldType mappedFieldType = new KNNVectorFieldType(
                    buildFullName(context),
                    metaValue,
                    dimension.getValue(),
                    knnMethodContext
                );
                if (knnMethodContext.getKnnEngine() == KNNEngine.LUCENE) {
                    log.debug(String.format("Use [LuceneFieldMapper] mapper for field [%s]", name));
                    LuceneFieldMapper.CreateLuceneFieldMapperInput createLuceneFieldMapperInput =
                        LuceneFieldMapper.CreateLuceneFieldMapperInput.builder()
                            .name(name)
                            .mappedFieldType(mappedFieldType)
                            .multiFields(multiFieldsBuilder)
                            .copyTo(copyToBuilder)
                            .ignoreMalformed(ignoreMalformed)
                            .stored(stored.get())
                            .hasDocValues(hasDocValues.get())
                            .knnMethodContext(knnMethodContext)
                            .build();
                    return new LuceneFieldMapper(createLuceneFieldMapperInput);
                }
                return new MethodFieldMapper(
                    name,
                    mappedFieldType,
                    multiFieldsBuilder,
                    copyToBuilder,
                    ignoreMalformed,
                    stored.get(),
                    hasDocValues.get(),
                    knnMethodContext
                );
            }

            String modelIdAsString = this.modelId.get();
            if (modelIdAsString != null) {
                // Because model information is stored in cluster metadata, we are unable to get it here. This is
                // because to get the cluster metadata, you need access to the cluster state. Because this code is
                // sometimes used to initialize the cluster state/update cluster state, we cannot get the state here
                // safely. So, we are unable to validate the model. The model gets validated during ingestion.

                return new ModelFieldMapper(
                    name,
                    new KNNVectorFieldType(buildFullName(context), metaValue, -1, knnMethodContext, modelIdAsString),
                    multiFieldsBuilder,
                    copyToBuilder,
                    ignoreMalformed,
                    stored.get(),
                    hasDocValues.get(),
                    modelDao,
                    modelIdAsString
                );
            }

            // Build legacy
            if (this.spaceType == null) {
                this.spaceType = LegacyFieldMapper.getSpaceType(context.indexSettings());
            }

            if (this.m == null) {
                this.m = LegacyFieldMapper.getM(context.indexSettings());
            }

            if (this.efConstruction == null) {
                this.efConstruction = LegacyFieldMapper.getEfConstruction(context.indexSettings());
            }

            return new LegacyFieldMapper(
                name,
                new KNNVectorFieldType(buildFullName(context), metaValue, dimension.getValue()),
                multiFieldsBuilder,
                copyToBuilder,
                ignoreMalformed,
                stored.get(),
                hasDocValues.get(),
                spaceType,
                m,
                efConstruction
            );
        }

        private KNNEngine validateMaxDimensions(final KNNMethodContext knnMethodContext) {
            final KNNEngine knnEngine;
            if (knnMethodContext != null) {
                knnEngine = knnMethodContext.getKnnEngine();
            } else {
                knnEngine = KNNEngine.DEFAULT;
            }
            if (dimension.getValue() > KNNEngine.getMaxDimensionByEngine(knnEngine)) {
                throw new IllegalArgumentException(
                    String.format(
                        "Dimension value cannot be greater than %s for vector: %s",
                        KNNEngine.getMaxDimensionByEngine(knnEngine),
                        name
                    )
                );
            }
            return knnEngine;
        }
    }

    public static class TypeParser implements Mapper.TypeParser {

        // Use a supplier here because in {@link org.opensearch.knn.KNNPlugin#getMappers()} the ModelDao has not yet
        // been initialized
        private Supplier<ModelDao> modelDaoSupplier;

        public TypeParser(Supplier<ModelDao> modelDaoSupplier) {
            this.modelDaoSupplier = modelDaoSupplier;
        }

        @Override
        public Mapper.Builder<?> parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            Builder builder = new KNNVectorFieldMapper.Builder(name, modelDaoSupplier.get());
            builder.parse(name, parserContext, node);

            // All <a
            // href="https://github.com/opensearch-project/OpenSearch/blob/1.0.0/server/src/main/java/org/opensearch/index/mapper/DocumentMapperParser.java#L115-L161">parsing</a>
            // is done before any mappers are built. Therefore, validation should be done during parsing
            // so that it can fail early.
            if (builder.knnMethodContext.get() != null && builder.modelId.get() != null) {
                throw new IllegalArgumentException(String.format("Method and model can not be both specified in the mapping: %s", name));
            }

            // Dimension should not be null unless modelId is used
            if (builder.dimension.getValue() == -1 && builder.modelId.get() == null) {
                throw new IllegalArgumentException(String.format("Dimension value missing for vector: %s", name));
            }

            return builder;
        }
    }

    @Getter
    public static class KNNVectorFieldType extends MappedFieldType {
        int dimension;
        String modelId;
        KNNMethodContext knnMethodContext;

        public KNNVectorFieldType(String name, Map<String, String> meta, int dimension) {
            this(name, meta, dimension, null, null);
        }

        public KNNVectorFieldType(String name, Map<String, String> meta, int dimension, KNNMethodContext knnMethodContext) {
            this(name, meta, dimension, knnMethodContext, null);
        }

        public KNNVectorFieldType(String name, Map<String, String> meta, int dimension, KNNMethodContext knnMethodContext, String modelId) {
            super(name, false, false, true, TextSearchInfo.NONE, meta);
            this.dimension = dimension;
            this.modelId = modelId;
            this.knnMethodContext = knnMethodContext;
        }

        @Override
        public ValueFetcher valueFetcher(QueryShardContext context, SearchLookup searchLookup, String format) {
            throw new UnsupportedOperationException("KNN Vector do not support fields search");
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        @Override
        public Query existsQuery(QueryShardContext context) {
            return new DocValuesFieldExistsQuery(name());
        }

        @Override
        public Query termQuery(Object value, QueryShardContext context) {
            throw new QueryShardException(
                context,
                String.format("KNN vector do not support exact searching, use KNN queries instead: [%s]", name())
            );
        }

        @Override
        public IndexFieldData.Builder fielddataBuilder(String fullyQualifiedIndexName, Supplier<SearchLookup> searchLookup) {
            failIfNoDocValues();
            return new KNNVectorIndexFieldData.Builder(name(), CoreValuesSourceType.BYTES);
        }
    }

    protected Explicit<Boolean> ignoreMalformed;
    protected boolean stored;
    protected boolean hasDocValues;
    protected Integer dimension;
    protected ModelDao modelDao;

    // These members map to parameters in the builder. They need to be declared in the abstract class due to the
    // "toType" function used in the builder. So, when adding a parameter, it needs to be added here, but set in a
    // subclass (if it is unique).
    protected KNNMethodContext knnMethod;
    protected String modelId;

    public KNNVectorFieldMapper(
        String simpleName,
        KNNVectorFieldType mappedFieldType,
        MultiFields multiFields,
        CopyTo copyTo,
        Explicit<Boolean> ignoreMalformed,
        boolean stored,
        boolean hasDocValues
    ) {
        super(simpleName, mappedFieldType, multiFields, copyTo);
        this.ignoreMalformed = ignoreMalformed;
        this.stored = stored;
        this.hasDocValues = hasDocValues;
        this.dimension = mappedFieldType.getDimension();
    }

    public KNNVectorFieldMapper clone() {
        return (KNNVectorFieldMapper) super.clone();
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    protected void parseCreateField(ParseContext context) throws IOException {
        parseCreateField(context, fieldType().getDimension());
    }

    protected void parseCreateField(ParseContext context, int dimension) throws IOException {

        validateIfKNNPluginEnabled();
        validateIfCircuitBreakerIsNotTriggered();

        Optional<float[]> arrayOptional = getFloatsFromContext(context, dimension);

        if (!arrayOptional.isPresent()) {
            return;
        }
        final float[] array = arrayOptional.get();
        VectorField point = new VectorField(name(), array, fieldType);

        context.doc().add(point);
        if (fieldType.stored()) {
            context.doc().add(new StoredField(name(), point.toString()));
        }
        context.path().remove();
    }

    void validateIfCircuitBreakerIsNotTriggered() {
        if (KNNSettings.isCircuitBreakerTriggered()) {
            throw new IllegalStateException(
                "Indexing knn vector fields is rejected as circuit breaker triggered. Check _opendistro/_knn/stats for detailed state"
            );
        }
    }

    void validateIfKNNPluginEnabled() {
        if (!KNNSettings.isKNNPluginEnabled()) {
            throw new IllegalStateException("KNN plugin is disabled. To enable update knn.plugin.enabled setting to true");
        }
    }

    Optional<float[]> getFloatsFromContext(ParseContext context, int dimension) throws IOException {
        context.path().add(simpleName());

        ArrayList<Float> vector = new ArrayList<>();
        XContentParser.Token token = context.parser().currentToken();
        float value;
        if (token == XContentParser.Token.START_ARRAY) {
            token = context.parser().nextToken();
            while (token != XContentParser.Token.END_ARRAY) {
                value = context.parser().floatValue();

                if (Float.isNaN(value)) {
                    throw new IllegalArgumentException("KNN vector values cannot be NaN");
                }

                if (Float.isInfinite(value)) {
                    throw new IllegalArgumentException("KNN vector values cannot be infinity");
                }

                vector.add(value);
                token = context.parser().nextToken();
            }
        } else if (token == XContentParser.Token.VALUE_NUMBER) {
            value = context.parser().floatValue();

            if (Float.isNaN(value)) {
                throw new IllegalArgumentException("KNN vector values cannot be NaN");
            }

            if (Float.isInfinite(value)) {
                throw new IllegalArgumentException("KNN vector values cannot be infinity");
            }

            vector.add(value);
            context.parser().nextToken();
        } else if (token == XContentParser.Token.VALUE_NULL) {
            context.path().remove();
            return Optional.empty();
        }

        if (dimension != vector.size()) {
            String errorMessage = String.format("Vector dimension mismatch. Expected: %d, Given: %d", dimension, vector.size());
            throw new IllegalArgumentException(errorMessage);
        }

        float[] array = new float[vector.size()];
        int i = 0;
        for (Float f : vector) {
            array[i++] = f;
        }
        return Optional.of(array);
    }

    @Override
    protected boolean docValuesByDefault() {
        return true;
    }

    @Override
    public ParametrizedFieldMapper.Builder getMergeBuilder() {
        return new KNNVectorFieldMapper.Builder(simpleName(), modelDao).init(this);
    }

    @Override
    public final boolean parsesArrayValue() {
        return true;
    }

    @Override
    public KNNVectorFieldType fieldType() {
        return (KNNVectorFieldType) super.fieldType();
    }

    @Override
    protected void doXContentBody(XContentBuilder builder, boolean includeDefaults, Params params) throws IOException {
        super.doXContentBody(builder, includeDefaults, params);
        if (includeDefaults || ignoreMalformed.explicit()) {
            builder.field(Names.IGNORE_MALFORMED, ignoreMalformed.value());
        }
    }

    public static class Names {
        public static final String IGNORE_MALFORMED = "ignore_malformed";
    }

    public static class Defaults {
        public static final Explicit<Boolean> IGNORE_MALFORMED = new Explicit<>(false, false);
        public static final FieldType FIELD_TYPE = new FieldType();

        static {
            FIELD_TYPE.setTokenized(false);
            FIELD_TYPE.setIndexOptions(IndexOptions.NONE);
            FIELD_TYPE.setDocValuesType(DocValuesType.BINARY);
            FIELD_TYPE.putAttribute(KNN_FIELD, "true"); // This attribute helps to determine knn field type
            FIELD_TYPE.freeze();
        }
    }
}
