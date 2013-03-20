package com.alexkasko.tasks;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static java.util.Collections.newSetFromMap;

/**
 * Engine for asynchronous multistage suspendable tasks.
 * Processes task stages one by one using provided {@link java.util.concurrent.Executor},
 * tasks stage will be updated between stages processing. Task status will be updated on error,
 * suspend or after successful processing of last stage.
 * Processors should call {@link TaskEngine#checkSuspended(long)} method periodically, it will
 * throw {@link TaskSuspendedException} on successful check.
 *
 * @author alexkasko
 * Date: 5/17/12
 * @see Task
 * @see TaskManager
 * @see TaskProcessorProvider
 * @see TaskStageChain
 * @see TaskStageProcessor
 * @see TaskSuspendedException
 */
public class TaskEngine implements Runnable {
    private static final Log logger = LogFactory.getLog(TaskEngine.class);

    private final Executor executor;
    private final TaskManager<? extends Task> manager;
    private final TaskProcessorProvider provider;
    // concurrent hash set creation
    private final Set<Long> awaitsSuspension = newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
    private final Object fireLock = new Object();

    /**
     * @param executor executor will be used to process separate stages
     * @param manager tasks DAO for all task state operations
     * @param provider stage processors provider
     */
    public TaskEngine(Executor executor, TaskManager<? extends Task> manager, TaskProcessorProvider provider) {
        if(null == executor) throw new IllegalArgumentException("Provided executor is null");
        if(null == manager) throw new IllegalArgumentException("Provided manager is null");
        if(null == provider) throw new IllegalArgumentException("Input provider is null");
        this.executor = executor;
        this.manager = manager;
        this.provider = provider;
    }

    /**
     * @param executor executor will be used to process separate stages
     * @param manager tasks DAO for all task state operations
     * @param provider stage processors provider
     * @param action action to be applied at startup
     */
    public TaskEngine(Executor executor, TaskManager<? extends Task> manager, TaskProcessorProvider provider,
                      StartupAction action) {
        this(executor, manager, provider);
        if (StartupAction.SUSPEND_RUNNUNG == action) suspendRunning();
    }

    private void suspendRunning() {
        for (Task task : manager.getRunning()) {
            String stage = task.getStageName();
            TaskStageChain stageChain = task.stageChain();
            TaskStageChain.Stage breakStage = stageChain.forName(stage);
            manager.updateStatusSuspended(task.getId());
            if (stage.equals(breakStage.getIntermediate())) {
                TaskStageChain.Stage previousStage = stageChain.previous(breakStage);
                manager.updateStage(task.getId(), previousStage.getCompleted());
            }
            logger.debug("Task was suspended on stage: '" + stage + "' at startup, id: '" + task.getId() + "'");
        }
    }

    /**
     * Sends tasks provided by {@link TaskManager#markProcessingAndLoad()}
     * to execution
     *
     * @return count of tasks sent for processing
     */
    public int fire() {
        synchronized (fireLock) {
            Collection<? extends Task> tasksToFire = manager.markProcessingAndLoad();
            if(0 == tasksToFire.size()) {
                logger.debug("No tasks to fire, returning to sleep");
                return 0;
            }
            // fire tasks
            int counter = 0;
            for(Task task : tasksToFire) {
                if(null == task) throw new IllegalArgumentException("Provided task is null, task list to fire: '" + tasksToFire + "'");
                awaitsSuspension.remove(task.getId()); // should be suspended during execution, not BEFORE it
                logger.debug("Firing task: '" + task + "'");
                Runnable runnable = new StageRunnable(task);
                executor.execute(runnable);
                counter += 1;
            }
            if(counter > 0 ) logger.info(counter + " tasks fired");
            return counter;
        }
    }

    /**
     * Scheduler friendly fire wrapper
     */
    @Override
    public void run() {
        fire();
    }

    /**
     * Mark task as suspended
     *
     * @param taskId task id
     * @return {@code false} if task was already suspended, {@code true} otherwise
     */
    public boolean suspend(long taskId) {
        logger.debug("Suspending task, id: '" + taskId + "'");
        return awaitsSuspension.add(taskId);
    }

    /**
     * Throws {@link TaskSuspendedException} on successful suspension check
     *
     * @param taskId task id
     * @throws TaskSuspendedException if task was already suspended
     */
    public void checkSuspended(long taskId) {
        if(awaitsSuspension.remove(taskId)) throw new TaskSuspendedException(taskId);
    }

    // Runnable instead of Callable is deliberate
    private class StageRunnable implements Runnable {
        private final Task task;

        StageRunnable(Task task) {
            if(null == task.stageChain()) throw new IllegalArgumentException("Task, id: '" + task.getId() + "' returns null stageChain");
            this.task = task;
        }

        @Override
        public void run() {
            try {
                runStages();
            } catch (Exception e) {
                logger.error("System error running task, id: '" + task.getId() + "'", e);
            }
        }

        @SuppressWarnings("unchecked")
        private void runStages() {
            TaskStageChain chain = task.stageChain();
            TaskStageChain.Stage stage = chain.forName(task.getStageName());
            boolean success = true;
            while (chain.hasNext(stage)) {
                if (whetherAwaitsSuspension()) {
                    success = false;
                    break;
                }
                stage = chain.next(stage);
                success = processStage(stage);
                if(!success) break;
            }
            if (success) {
                boolean justSuspended = whetherAwaitsSuspension();
                if (!justSuspended) {
                    manager.updateStatusDefault(task.getId());
                }
            }
        }

        private boolean processStage(TaskStageChain.Stage stage) {
            try {
                logger.debug("Starting stage: '" + stage.getIntermediate() + "' for task, id: '" + task.getId() + "'");
                TaskStageProcessor processor = provider.provide(stage.getProcessorId());
                if (null == processor) throw new IllegalArgumentException("Null processor returned for id: '" + stage.getProcessorId() + "'");
                manager.updateStage(task.getId(), stage.getIntermediate());
                fireBeforeListeners(processor);
                processor.process(task.getId());
                fireAfterListeners(processor);
                logger.debug("Stage: '" + stage.getCompleted() + "' completed for task, id: '" + task.getId() + "'");
                manager.updateStage(task.getId(), stage.getCompleted());
                return true;
            } catch (TaskSuspendedException e) {
                logger.info("Task, id: '" + task.getId() + "' was suspended on stage: '" + stage.getIntermediate() + "'");
                manager.updateStatusSuspended(task.getId());
                manager.updateStage(task.getId(), task.stageChain().previous(stage).getCompleted());
                return false;
            } catch (Exception e) {
                logger.error("Task, id: '" + task.getId() + "' caused error on stage: '" + stage.getIntermediate() + "'", e);
                manager.updateStatusError(task.getId(), e, task.stageChain().previous(stage).getCompleted());
                return false;
            }
        }

        private void fireBeforeListeners(TaskStageProcessor processor) {
            if(processor instanceof TaskStageListenableProcessor) {
                TaskStageListenableProcessor listen = (TaskStageListenableProcessor) processor;
                for(TaskStageListener li : listen.beforeStartListeners()) {
                    li.fire(task.getId());
                }
            }
        }

        private void fireAfterListeners(TaskStageProcessor processor) {
            if(processor instanceof TaskStageListenableProcessor) {
                TaskStageListenableProcessor listen = (TaskStageListenableProcessor) processor;
                for(TaskStageListener li : listen.afterFinishListeners()) {
                    li.fire(task.getId());
                }
            }
        }

        private boolean whetherAwaitsSuspension() {
            if (!awaitsSuspension.remove(task.getId())) return false;
            logger.info("Task, id: '" + task.getId() + "' was suspended, terminating execution");
            manager.updateStatusSuspended(task.getId());
            return true;
        }
    }

    /**
     * Actions that could be applied at {@link TaskEngine} startup.
     */
    public static enum StartupAction {
        SUSPEND_RUNNUNG;
    }
}