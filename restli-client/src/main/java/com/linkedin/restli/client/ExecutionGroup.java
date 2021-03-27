/*
   Copyright (c) 2021 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.linkedin.restli.client;

import com.linkedin.parseq.Engine;
import com.linkedin.parseq.ParTask;
import com.linkedin.parseq.Task;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * {@link ExecutionGroup} is used to group requests generated by FluentApis, so the batching support provided
 * by the underlying Rest.Li ParSeq client can be leveraged.
 *
 * The request grouped by execution group will be further grouped by Client so requests will
 * be batched per Client.
 *
 * There are two way to use this
 * Method 1: Using it with the fluent api and ask the executionGroup to execute explicitly.
 * Example:
 * <blockquote>
 *   <pre>
 *     ExecutionGroup eg;
 *     {@code <YourClient>}.get({@code <Parameter>}, eg); // This request will be passed into ExecutionGroup
 *     eg.execute();
 *   </pre>
 * </blockquote>
 *
 * Please be noted these when passing around the {@link ExecutionGroup} instance:
 * - {@link ExecutionGroup} can only be executed once. Once executed,
 *   no task should be added to the same {@link ExecutionGroup} anymore.
 * - {@link ExecutionGroup} implementations for adding and executing the requests are not thread-safe.
 * Based on these, it is recommended that the user call {@link ExecutionGroup#execute()} method in a decisive point of time,
 * as if setting a synchronization barrier, and create a new instance if firing another batch call is needed.
 * For example, one can use the last composed stage to execute the {@link ExecutionGroup}
 *
 * Method 2: Use it inside a lambda function. Corresponding FluentAPIs used inside this lambda will be batched.
 * Note in this style, you can still optionally pass the Client type as parameter to specifies requests from which
 * clients the requests need to be batched on. If clients not provided as arguments,
 * all FluentAPI requests will be batched.
 *
 * Example:
 * <blockquote>
 *   <pre>
 *     new ExecutionGroup().batchOn(() -> {
 *       {@code <YourClient>}.get({@code <Parameter1>});
 *       {@code <YourClient>}.get({@code <Parameter2>});
 *     });
 *   </pre>
 * </blockquote>
 * Note: One can use nested executiongroup and each lambda clause have a separate scope.
 * Example:
 * <blockquote>
 *   <pre>
 *     ExecutionGroup otherEg;
 *     new ExecutionGroup().batchOn(() -> {
 *       {@code <YourClient>}.get({@code <Parameter1>}); // implicitly add to the ExecutionGroup which created this lambda
 *       {@code <YourClient>}.get({@code <Parameter2>}); // implicitly add to the ExecutionGroup which created this lambda
 *       new ExecutionGroup().batchOn(() -> {
 *         {@code <YourClient2>}.get({@code <Parameter3>});
 *         {@code <YourClient2>}.get({@code <Parameter4>});
 *       }); // this execution group will not be affecting the outer execution group, so Parameter 3 and 4 will be batched
 *       // adding to another execution group explicitly so will not be batched together with other implicit calls in this lambda clause.
 *       {@code <YourClient>}.get({@code <Parameter3>}, anotherEg);
 *     }); // get call from {@code <YourClient>} with parameter1 and parameter2 will be batched
 *   </pre>
 * </blockquote>
 *
 *
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class ExecutionGroup
{
  private final Map<FluentClient, List<Task<?>>> _clientToTaskListMap = new HashMap<>();
  private final Engine _engine;
  private boolean _fired = false;

  private List<FluentClient> _fluentClientAll; // filled by UClient when executionGroup is created; Used for batchOn
  static final String MULTIPLE_EXECUTION_ERROR = "Operation not supported, the executionGroup has already been executed.";
  static final String ADD_AFTER_EXECUTION_ERROR = "Operation not supported, the execution group has already been executed.";

  /**
   * This constructor will be called by the UniversalClient and will not be called by API users directly
   * @param engine
   */
  /* package private */
  public ExecutionGroup(Engine engine)
  {
    _engine = engine;
  }

  /**
   * Execute all the tasks that added to {@link ExecutionGroup} through ParSeq Engine
   */
  public void execute()
  {
    if (_fired)
    {
      throw new IllegalStateException(MULTIPLE_EXECUTION_ERROR);
    }
    _fired = true;
    for (Map.Entry<FluentClient, List<Task<?>>> entry : _clientToTaskListMap.entrySet()) {
      List<Task<?>> taskList = entry.getValue();
      // the Task.par(Iterable) version does not fast-fail comparing to Task.par(Task...)
      ParTask<Object> perFluentClientTasks =
          Task.par(taskList);
      _clientToTaskListMap.remove(entry.getKey());
      // starts a plan for tasks from one client due to performance consideration
      // TODO: optimize, use scheduleAndRun
      _engine.run(perFluentClientTasks);
    }
  }

  /**
   * Run user's logic provided in lambda function and batch related requests made using FluentAPI inside this lambda function
   *
   * Note the FluentAPI requests that take the ExecutionGroup instance as an explicit parameter will not be batched.
   * Also everytime this method is called, it creates a separate ExecutionGroup so nested ExecutionGroup won't be affected.
   *
   * @param runnable the runnable that executes user's logic
   * @param fluentClients the fluentClients whose requests will be batched, if None specified, all fluentClients call
   *                      will be batched.
   * @throws Exception
   */
  // TODO: (1) This should be called by UniversalClient
  // TODO: (2) this be called by FluentClient as a convenient method
  public void batchOn(Runnable runnable, FluentClient... fluentClients) throws Exception
  {
    List<FluentClient> batchedClients =
        fluentClients.length > 0 ? new ArrayList<FluentClient>(Arrays.asList(fluentClients))
            : _fluentClientAll;

    for (FluentClient fluentClient : batchedClients) {
      fluentClient.setExecutionGroup(this);
    }
    try {
      runnable.run();
      this.execute();
    } finally {
      for (FluentClient fluentClient : batchedClients) {
        fluentClient.removeExecutionGroup();
      }
    }
  }

  /**
   * To add ParSeq tasks to the this {@link ExecutionGroup}.
   * The tasks belong to same {@link FluentClient} are supposed to be run as a batch together
   *
   * @param client the {@link FluentClient} that this tasks came from.
   * @param tasks the tasks to be added to the {@link FluentClient}, will be grouped by the client
   */
  public void addTaskByFluentClient(FluentClient client, Task<?>... tasks)
  {
    if (!_fired)
    {
      _clientToTaskListMap.computeIfAbsent(client, (v) -> new ArrayList<>()).addAll(Arrays.asList(tasks));
    }
    else
    {
      throw new IllegalStateException(ADD_AFTER_EXECUTION_ERROR);
    }
  }


  /**
   * Add all FluentClients that can be batched on.
   *
   * The clients stored in this list will be used
   * as the default clients to be batched on if the user does not specify
   *
   * @param fluentClientAll all the FluentClients that can be batched on
   */
  void setFluentClientAll(List<FluentClient> fluentClientAll)
  {
    _fluentClientAll = fluentClientAll;
  }

  Map<FluentClient, List<Task<?>>> getClientToTaskListMap()
  {
    return _clientToTaskListMap;
  }
}
