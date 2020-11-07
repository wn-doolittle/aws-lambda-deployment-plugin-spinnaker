/*
 * Copyright 2018 Amazon.com, Inc. or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.amazon.aws.spinnaker.plugin.lambda.upsert;

import com.amazon.aws.spinnaker.plugin.lambda.LambdaCloudOperationOutput;
import com.amazon.aws.spinnaker.plugin.lambda.LambdaStageBaseTask;
import com.amazon.aws.spinnaker.plugin.lambda.upsert.model.LambdaDeploymentInput;
import com.amazon.aws.spinnaker.plugin.lambda.utils.*;
import com.netflix.spinnaker.orca.api.pipeline.TaskResult;
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus;
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution;
import com.netflix.spinnaker.orca.clouddriver.config.CloudDriverConfigurationProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class LambdaCreateTask implements LambdaStageBaseTask {
    private static Logger logger = LoggerFactory.getLogger(LambdaCreateTask.class);
    private static String CLOUDDRIVER_CREATE_PATH = "/aws/ops/createLambdaFunction";

    private final CloudDriverConfigurationProperties props;
    private  String cloudDriverUrl;

    private final LambdaCloudDriverUtils utils;

    @NotNull
    @Override
    public TaskResult execute(@NotNull StageExecution stage) {
        logger.debug("Executing LambdaDeploymentTask...");
        cloudDriverUrl = props.getCloudDriverBaseUrl();
        prepareTask(stage);
        LambdaDeploymentInput ldi = utils.getInput(stage, LambdaDeploymentInput.class);
        List<String> errors = new ArrayList<>();
        if (!utils.validateUpsertLambdaInput(ldi, errors)) {
            return this.formErrorListTaskResult(stage, errors);
        }
        ldi.setAppName(stage.getExecution().getApplication());
        LambdaGetInput lgi = utils.getInput(stage, LambdaGetInput.class);
        lgi.setAppName(stage.getExecution().getApplication());
        LambdaDefinition lambdaDefinition = utils.retrieveLambda(lgi);
        if (lambdaDefinition != null) {
            logger.debug("noOp. Lambda already exists. only needs updating.");
            fillTaskContext(stage, lambdaDefinition);
            addToOutput(stage, LambdaStageConstants.lambaCreatedKey, Boolean.FALSE);
            addToTaskContext(stage, LambdaStageConstants.lambaCreatedKey, Boolean.FALSE);
            addToOutput(stage, LambdaStageConstants.originalRevisionIdKey, lambdaDefinition.getRevisionId());
            return taskComplete(stage);
        }
        addToOutput(stage, LambdaStageConstants.lambaCreatedKey, Boolean.TRUE);
        addToTaskContext(stage, LambdaStageConstants.lambaCreatedKey, Boolean.TRUE);
        LambdaCloudOperationOutput output = createLambda(stage);
        addCloudOperationToContext(stage, output, LambdaStageConstants.createdUrlKey);
        return taskComplete(stage);
    }

    private LambdaCloudOperationOutput createLambda(StageExecution stage) {
        LambdaDeploymentInput ldi = utils.getInput(stage, LambdaDeploymentInput.class);
        ldi.setAppName(stage.getExecution().getApplication());
        ldi.setCredentials(ldi.getAccount());
        String endPoint = cloudDriverUrl + CLOUDDRIVER_CREATE_PATH ;
        String rawString = utils.asString(ldi);
        LambdaCloudDriverResponse respObj = utils.postToCloudDriver(endPoint, rawString);
        String url = cloudDriverUrl + respObj.getResourceUri();
        logger.debug("Posted to cloudDriver for createLambda: " + url);
        LambdaCloudOperationOutput operationOutput = LambdaCloudOperationOutput.builder().resourceId(respObj.getId()).url(url).build();
        return operationOutput;
    }

    private void fillTaskContext(StageExecution stage, LambdaDefinition lf) {
        addToTaskContext(stage, LambdaStageConstants.lambaCreatedKey, Boolean.FALSE);
        addToTaskContext(stage, LambdaStageConstants.lambdaObjectKey, lf);
        addToTaskContext(stage, LambdaStageConstants.originalRevisionIdKey, lf.getRevisionId());
    }

    @Nullable
    @Override
    public TaskResult onTimeout(@NotNull StageExecution stage) {
        return TaskResult.builder(ExecutionStatus.SKIPPED).build();
    }

    @Override
    public void onCancel(@NotNull StageExecution stage) {
    }
}
