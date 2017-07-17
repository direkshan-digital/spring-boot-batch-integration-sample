package com.uesleilima.spring.batch.integration.config;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.configuration.support.JobRegistryBeanPostProcessor;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.integration.launch.JobLaunchRequest;
import org.springframework.batch.integration.launch.JobLaunchingMessageHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.dsl.channel.MessageChannels;
import org.springframework.integration.dsl.core.Pollers;
import org.springframework.integration.file.FileReadingMessageSource;
import org.springframework.integration.file.filters.CompositeFileListFilter;
import org.springframework.integration.file.filters.SimplePatternFileListFilter;
import org.springframework.integration.scheduling.PollerMetadata;
import org.springframework.messaging.MessageChannel;

import com.uesleilima.spring.batch.integration.components.LastModifiedFileFilter;
import com.uesleilima.spring.batch.integration.domain.EntryRepository;

@Configuration
@EnableIntegration
public class IntegrationConfig {

	private static final Logger log = LoggerFactory.getLogger(IntegrationConfig.class);

	public static final String DIRECTORY = "C:\\Temp\\files-to-process\\";
	public static final Long DIRECTORY_POOL_RATE = (long) 1000;

	@Autowired
	private JobLauncher jobLauncher;

	@Autowired
	private JobRegistry jobRegistry;

	@Autowired
	private EntryRepository repository;

	@Bean
	public JobRegistryBeanPostProcessor jobRegistryInitializer() {
		JobRegistryBeanPostProcessor initializer = new JobRegistryBeanPostProcessor();
		initializer.setJobRegistry(jobRegistry);
		return initializer;
	}

	@Bean
	public IntegrationFlow processFilesFlow() {
		return IntegrationFlows.from(fileReadingMessageSource(), c -> c.poller(poller()))
				.channel(fileInputChannel())
				.<File, JobLaunchRequest>transform(f -> transformFileToRequest(f))
				.channel(jobRequestChannel())
				.handle(jobRequestHandler())
				.<JobExecution, String>transform(e -> transformJobExecutionToStatus(e))
				.channel(jobStatusChannel())
				.handle(s -> log.info(s.toString()))
				.get();
	}

	@Bean(name = PollerMetadata.DEFAULT_POLLER)
	public PollerMetadata poller() {
		return Pollers.fixedRate(DIRECTORY_POOL_RATE).get();
	}

	@Bean
	public MessageChannel fileInputChannel() {
		return MessageChannels.direct().get();
	}

	@Bean
	public MessageChannel jobRequestChannel() {
		return MessageChannels.direct().get();
	}

	@Bean
	public MessageChannel jobStatusChannel() {
		return MessageChannels.direct().get();
	}

	@Bean
	public MessageSource<File> fileReadingMessageSource() {
		CompositeFileListFilter<File> filters = new CompositeFileListFilter<>();
		filters.addFilter(new SimplePatternFileListFilter("*.txt"));
		filters.addFilter(new LastModifiedFileFilter());

		FileReadingMessageSource source = new FileReadingMessageSource();
		source.setAutoCreateDirectory(true);
		source.setDirectory(new File(DIRECTORY));
		source.setFilter(filters);

		return source;
	}

	@Bean
	public JobLaunchingMessageHandler jobRequestHandler() {
		return new JobLaunchingMessageHandler(jobLauncher);
	}

	public JobLaunchRequest transformFileToRequest(File file) {
		log.debug("Creating request");

		Job job = getJobByFileName(file);

		log.debug("Job = " + job.getName());

		JobParametersBuilder paramsBuilder = new JobParametersBuilder();
		paramsBuilder.addString("input.file.name", file.getPath());

		log.debug("Parameters = " + paramsBuilder.toString());

		JobLaunchRequest request = new JobLaunchRequest(job, paramsBuilder.toJobParameters());
		return request;
	}

	public String transformJobExecutionToStatus(JobExecution execution) {
		DateFormat formatter = new SimpleDateFormat("yyyy/MM/dd hh:mm:ss.SS");
		StringBuilder builder = new StringBuilder();

		builder.append(execution.getJobInstance().getJobName());

		BatchStatus evaluatedStatus = endingBatchStatus(execution);
		if (evaluatedStatus == BatchStatus.COMPLETED || evaluatedStatus.compareTo(BatchStatus.STARTED) > 0) {
			builder.append(" has completed with a status of " + execution.getStatus().name() + " at "
					+ formatter.format(new Date()));
			
			builder.append(" with " + repository.count() + " processed records.");
		} else {
			builder.append(" has started at " + formatter.format(new Date()));
		}

		return builder.toString();
	}

	private BatchStatus endingBatchStatus(JobExecution execution) {
		BatchStatus status = execution.getStatus();
		Collection<StepExecution> stepExecutions = execution.getStepExecutions();

		if (stepExecutions.size() > 0) {
			for (StepExecution stepExecution : stepExecutions) {
				if (stepExecution.getStatus().equals(BatchStatus.FAILED)) {
					status = BatchStatus.FAILED;
					break;
				} else {
					status = BatchStatus.COMPLETED;
				}
			}
		}

		return status;
	}

	private Job getJobByFileName(File file) {
		try {
			return jobRegistry.getJob(BatchConfig.JOB_NAME);
		} catch (NoSuchJobException e) {
			log.error(e.getLocalizedMessage());
			e.printStackTrace();
			return null;
		}
	}
}
