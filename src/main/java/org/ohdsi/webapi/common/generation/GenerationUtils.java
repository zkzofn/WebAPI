package org.ohdsi.webapi.common.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.ohdsi.webapi.Constants;
import org.ohdsi.webapi.cohortcharacterization.CreateCohortTableTasklet;
import org.ohdsi.webapi.cohortcharacterization.DropCohortTableListener;
import org.ohdsi.webapi.cohortcharacterization.GenerateLocalCohortTasklet;
import org.ohdsi.webapi.cohortdefinition.CohortDefinition;
import org.ohdsi.webapi.estimation.specification.EstimationAnalysis;
import org.ohdsi.webapi.executionengine.entity.AnalysisFile;
import org.ohdsi.webapi.executionengine.job.CreateAnalysisTasklet;
import org.ohdsi.webapi.executionengine.job.ExecutionEngineCallbackTasklet;
import org.ohdsi.webapi.executionengine.job.RunExecutionEngineTasklet;
import org.ohdsi.webapi.executionengine.repository.AnalysisExecutionRepository;
import org.ohdsi.webapi.executionengine.service.ScriptExecutionService;
import org.ohdsi.webapi.service.AbstractDaoService;
import org.ohdsi.webapi.service.CohortGenerationService;
import org.ohdsi.webapi.service.GenerationTaskExceptionHandler;
import org.ohdsi.webapi.service.JobService;
import org.ohdsi.webapi.service.SourceService;
import org.ohdsi.webapi.source.Source;
import org.ohdsi.webapi.sqlrender.SourceAwareSqlRender;
import org.ohdsi.webapi.util.SessionUtils;
import org.ohdsi.webapi.util.SourceUtils;
import org.ohdsi.webapi.util.TempTableCleanupManager;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.job.builder.SimpleJobBuilder;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import static org.ohdsi.webapi.Constants.Params.SESSION_ID;
import static org.ohdsi.webapi.Constants.Params.TARGET_TABLE;

@Component
public class GenerationUtils extends AbstractDaoService {

    private StepBuilderFactory stepBuilderFactory;
    private TransactionTemplate transactionTemplate;
    private CohortGenerationService cohortGenerationService;
    private SourceService sourceService;
    private JobBuilderFactory jobBuilders;
    private JobService jobService;
    private final SourceAwareSqlRender sourceAwareSqlRender;
    private final ScriptExecutionService executionService;
    private final AnalysisExecutionRepository analysisExecutionRepository;
    private final EntityManager entityManager;

    public GenerationUtils(StepBuilderFactory stepBuilderFactory,
                           TransactionTemplate transactionTemplate,
                           CohortGenerationService cohortGenerationService,
                           SourceService sourceService,
                           JobBuilderFactory jobBuilders,
                           SourceAwareSqlRender sourceAwareSqlRender,
                           JobService jobService,
                           ScriptExecutionService executionService,
                           AnalysisExecutionRepository analysisExecutionRepository,
                           EntityManager entityManager) {

        this.stepBuilderFactory = stepBuilderFactory;
        this.transactionTemplate = transactionTemplate;
        this.cohortGenerationService = cohortGenerationService;
        this.sourceService = sourceService;
        this.jobBuilders = jobBuilders;
        this.sourceAwareSqlRender = sourceAwareSqlRender;
        this.jobService = jobService;
        this.executionService = executionService;
        this.analysisExecutionRepository = analysisExecutionRepository;
        this.entityManager = entityManager;
    }

    public static String getTempCohortTableName(String sessionId) {

        return Constants.TEMP_COHORT_TABLE_PREFIX + sessionId;
    }

    public SimpleJobBuilder buildJobForCohortBasedAnalysisTasklet(
            String analysisTypeName,
            Source source,
            JobParametersBuilder builder,
            JdbcTemplate jdbcTemplate,
            Function<ChunkContext, Collection<CohortDefinition>> cohortGetter,
            CancelableTasklet analysisTasklet
    ) {

        final String sessionId = SessionUtils.sessionId();
        addSessionParams(builder, sessionId);

        TempTableCleanupManager cleanupManager = new TempTableCleanupManager(
                getSourceJdbcTemplate(source),
                transactionTemplate,
                source.getSourceDialect(),
                sessionId,
                SourceUtils.getTempQualifier(source)
        );

        GenerationTaskExceptionHandler exceptionHandler = new GenerationTaskExceptionHandler(cleanupManager);

        CreateCohortTableTasklet createCohortTableTasklet = new CreateCohortTableTasklet(jdbcTemplate, transactionTemplate, sourceService, sourceAwareSqlRender);
        Step createCohortTableStep = stepBuilderFactory.get(analysisTypeName + ".createCohortTable")
                .tasklet(createCohortTableTasklet)
                .build();

        GenerateLocalCohortTasklet generateLocalCohortTasklet = new GenerateLocalCohortTasklet(
                transactionTemplate,
                cohortGenerationService,
                sourceService,
                jobService,
                cohortGetter
        );
        Step generateLocalCohortStep = stepBuilderFactory.get(analysisTypeName + ".generateCohort")
                .tasklet(generateLocalCohortTasklet)
                .build();

        Step generateCohortFeaturesStep = stepBuilderFactory.get(analysisTypeName + ".generate")
                .tasklet(analysisTasklet)
                .exceptionHandler(exceptionHandler)
                .build();

        DropCohortTableListener dropCohortTableListener = new DropCohortTableListener(jdbcTemplate, transactionTemplate, sourceService, sourceAwareSqlRender);

        SimpleJobBuilder generateJobBuilder = jobBuilders.get(analysisTypeName)
                .start(createCohortTableStep)
                .next(generateLocalCohortStep)
                .next(generateCohortFeaturesStep)
                .listener(dropCohortTableListener)
                .listener(new AutoremoveJobListener(jobService));

        return generateJobBuilder;
    }

    protected void addSessionParams(JobParametersBuilder builder, String sessionId) {
        builder.addString(SESSION_ID, sessionId);
        builder.addString(TARGET_TABLE, GenerationUtils.getTempCohortTableName(sessionId));
    }

    public SimpleJobBuilder buildJobForExecutionEngineBasedAnalysisTasklet(String analysisTypeName,
                                                              Source source,
                                                              JobParametersBuilder builder,
                                                              List<AnalysisFile> analysisFiles) {

        final String sessionId = SessionUtils.sessionId();
        addSessionParams(builder, sessionId);

        CreateAnalysisTasklet createAnalysisTasklet = new CreateAnalysisTasklet(executionService, source.getSourceKey(), analysisFiles);
        RunExecutionEngineTasklet runExecutionEngineTasklet = new RunExecutionEngineTasklet(executionService, source, analysisFiles);
        ExecutionEngineCallbackTasklet callbackTasklet = new ExecutionEngineCallbackTasklet(analysisExecutionRepository, entityManager);

        Step createAnalysisExecutionStep = stepBuilderFactory.get(analysisTypeName + ".createAnalysisExecution")
                .tasklet(createAnalysisTasklet)
                .build();

        Step runExecutionStep = stepBuilderFactory.get(analysisTypeName + ".startExecutionEngine")
                .tasklet(runExecutionEngineTasklet)
                .build();

        Step waitCallbackStep = stepBuilderFactory.get(analysisTypeName + ".waitForCallback")
                .tasklet(callbackTasklet)
                .build();

        JdbcTemplate jdbcTemplate = getSourceJdbcTemplate(source);
        DropCohortTableListener dropCohortTableListener = new DropCohortTableListener(jdbcTemplate, transactionTemplate, sourceService, sourceAwareSqlRender);

        return jobBuilders.get(analysisTypeName)
                .start(createAnalysisExecutionStep)
                .next(runExecutionStep)
                .next(waitCallbackStep)
                .listener(dropCohortTableListener)
                .listener(new AutoremoveJobListener(jobService));
    }

    // NOTE: This should be replaced with SSA.serialize once issue
    // noted in the download function is addressed.
    public <T> String serializeAnalysis(T analysis) throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.disable(
                MapperFeature.AUTO_DETECT_CREATORS,
                MapperFeature.AUTO_DETECT_GETTERS,
                MapperFeature.AUTO_DETECT_IS_GETTERS
        );

        objectMapper.disable(
                SerializationFeature.FAIL_ON_EMPTY_BEANS
        );

        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        //objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        return objectMapper.writeValueAsString(analysis);
    }
}