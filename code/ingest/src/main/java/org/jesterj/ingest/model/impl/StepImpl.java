/*
 * Copyright 2014-2016 Needham Software LLC
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
 */

package org.jesterj.ingest.model.impl;

import com.datastax.driver.core.exceptions.NoHostAvailableException;
import net.jini.space.JavaSpace;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.appender.AppenderLoggingException;
import org.jesterj.ingest.Main;
import org.jesterj.ingest.config.Transient;
import org.jesterj.ingest.logging.JesterJAppender;
import org.jesterj.ingest.model.*;
import org.jesterj.ingest.processors.DefaultWarningProcessor;
import org.jesterj.ingest.routers.RouteByStepName;
import org.jesterj.ingest.utils.Cloner;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Spliterator;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/*
 * Created with IntelliJ IDEA.
 * User: gus
 * Date: 9/28/14
 */

/**
 * The class that is used to run {@link DocumentProcessor}s. This class takes care of the handling of the document
 * ensures it is properly received and passed on. This class is not normally overridden, to implement custom
 * processing logic write a class that implements <tt>DocumentProcessor</tt> and then build a stepImpl that uses
 * an instance of your processor. Also note that one does not normally call build on a StepImpl or any of it's
 * subclasses. The builder for this class is provided to a {@link org.jesterj.ingest.model.impl.PlanImpl.Builder} so
 * that the plan can validate the ordering of the steps and assemble the entire plan as an immutable DAG.
 */
public class StepImpl implements Step {

  private static final Logger log = LogManager.getLogger();

  private LinkedBlockingQueue<Document> queue;
  private int batchSize; // no concurrency by default
  private LinkedHashMap<String, Step> nextSteps = new LinkedHashMap<>();
  private volatile boolean active;
  private JavaSpace outputSpace;
  private JavaSpace inputSpace;
  private String stepName;
  private Router router = new RouteByStepName();
  private DocumentProcessor processor = new DefaultWarningProcessor();
  private Thread worker;
  private Plan plan;
  private Cloner<Document> cloner = new Cloner<>();

  private List<Runnable> deferred = new ArrayList<>();
  private final Object sideEffectListLock = new Object();
  private Step[] possibleSideEffects;

  StepImpl() {
  }

  public Spliterator<Document> spliterator() {
    return queue.spliterator();
  }

  public boolean isEmpty() {
    return queue.isEmpty();
  }

  public Document element() {
    return queue.element();
  }

  public Document poll(long timeout, TimeUnit unit) throws InterruptedException {
    return queue.poll(timeout, unit);
  }

  public Stream<Document> parallelStream() {
    return queue.parallelStream();
  }

  public Document take() throws InterruptedException {
    return queue.take();
  }

  public void clear() {
    queue.clear();
  }

  public Iterator<Document> iterator() {
    return queue.iterator();
  }

  public boolean containsAll(Collection<?> c) {
    throw new UnsupportedOperationException("bulk operations not supported for steps");
  }

  public <T> T[] toArray(T[] a) {
    //noinspection SuspiciousToArrayCall
    return queue.toArray(a);
  }

  public boolean addAll(Collection<? extends Document> c) {
    throw new UnsupportedOperationException("bulk operations supported for steps");
  }

  public int remainingCapacity() {
    return queue.remainingCapacity();
  }

  public Stream<Document> stream() {
    return queue.stream();
  }

  public boolean offer(Document document, long timeout, TimeUnit unit) throws InterruptedException {
    return queue.offer(document, timeout, unit);
  }

  public boolean offer(Document document) {
    return queue.offer(document);
  }

  public Document poll() {
    return queue.poll();
  }

  public int drainTo(Collection<? super Document> c, int maxElements) {
    return queue.drainTo(c, maxElements);
  }

  public boolean retainAll(Collection<?> c) {
    throw new UnsupportedOperationException("bulk operations supported for steps");
  }

  public void put(Document document) throws InterruptedException {
    queue.put(document);
  }

  public Document peek() {
    return queue.peek();
  }

  public int size() {
    return queue.size();
  }

  public boolean contains(Object o) {
    return queue.contains(o);
  }

  public boolean remove(Object o) {
    return queue.remove(o);
  }

  public boolean removeAll(Collection<?> c) {
    return queue.removeAll(c);
  }

  public boolean add(Document document) {
    return queue.add(document);
  }

  public void forEach(Consumer<? super Document> action) {
    queue.forEach(action);
  }

  public Document remove() {
    return queue.remove();
  }

  public Object[] toArray() {
    return queue.toArray();
  }

  public boolean removeIf(Predicate<? super Document> filter) {
    return queue.removeIf(filter);
  }

  public int drainTo(Collection<? super Document> c) {
    return queue.drainTo(c);
  }

  @Override
  public int getBatchSize() {
    return batchSize;
  }

  @Override
  public Step[] getNext(Document doc) {
    if (nextSteps.size() == 0) return null;
    if (nextSteps.size() == 1) return new Step[]{nextSteps.values().iterator().next()};
    return router.route(doc, nextSteps);
  }

  @Override
  public Step[] getSubsequentSteps() {
    //TODO: Something that isn't brain dead
    return new Step[0];
  }

  @Override
  public Plan getPlan() {
    return this.plan;
  }

  /**
   * Only to be used in PlanImpl
   *
   * @param plan the plan to which this step is attached
   */
  void setPlan(Plan plan) {
    this.plan = plan;
  }

  @Override
  public void advertise() {

  }

  @Override
  public void stopAdvertising() {

  }

  @Override
  public void acceptJiniRequests() {

  }

  @Override
  public void denyJiniRequests() {

  }

  @Override
  public boolean readyForJiniRequests() {
    return false;
  }

  @Override
  public void activate() {
    log.info("Starting {} ", getName());
    if (worker == null || !worker.isAlive()) {
      log.info("Starting new thread for {} ", getName());
      worker = new Thread(this);
      worker.setName("jj-worker-" + this.stepName);
      worker.setDaemon(true);
      worker.start();
    }
    this.active = true;
  }

  @Override
  public void deactivate() {
    this.active = false;
    if (worker != null) {
      try {
        worker.join(1000);
        if (worker.isAlive()) {
          log.warn("{} was slow shutting down, interrupting..", getName());
          worker.interrupt();
        }
      } catch (InterruptedException e) {
        // ignore
      }
    }
    processor.close();
  }

  @Transient
  @Override
  public boolean isActive() {
    return this.active;
  }

  @Override
  public void sendToNext(Document doc) {
    pushToNextIfOk(doc);
  }

  @Override
  public Step[] getPossibleSideEffects() {
    if (this.possibleSideEffects == null) {
      synchronized (sideEffectListLock) {
        if (this.possibleSideEffects == null) {
          if (nextSteps.isEmpty()) {
            if (processor.hasExternalSideEffects()) {
              possibleSideEffects = new Step[] {this};
            } else {
              // oddball case! but shoudlnt
              possibleSideEffects = new Step[0];
            }
          } else {
            Step[][] subEffects = new Step[nextSteps.size()][];
            ArrayList<Step> values = new ArrayList<>(nextSteps.values());
            for (int i = 0; i < values.size(); i++) {
              subEffects[i] = values.get(i).getPossibleSideEffects();
            }
            ArrayList<Step> tmp = new ArrayList<>();
            for (Step[] subEffect : subEffects) {
              Collections.addAll(tmp, subEffect);
            }
            if (processor.hasExternalSideEffects()) {
              tmp.add(this);
            }
            possibleSideEffects = tmp.toArray(new Step[tmp.size()]);
          }
        }
      }
    }
    return possibleSideEffects;
  }

  LinkedHashMap<String, Step> getNextSteps() {
    return nextSteps;
  }

  private void pushToNextIfOk(Document document) {
    log.trace("starting push to next if ok {} for {}", getName(), document.getId());
    if (document.getStatus() == Status.PROCESSING) {
      reportDocStatus(Status.PROCESSING, document, "{} finished processing {}", getName(), document.getId());
      Step[] next = getNext(document);
      if (next == null) {
        reportDocStatus(Status.DROPPED, document, "No qualifying next step found by {} in {}", router, getName());
        return;
      }
      if (next.length == 1) {
        // for single step no need to clone
        pushToStep(document, next[0]);
      } else {
        log.info("Distributing doc {} to {} ", document.getId(), Arrays.stream(next).map(Step::getName).collect(Collectors.toList()));
        Deque<Document> clones = new ArrayDeque<>(next.length);
        for (int i = 0; i < next.length; i++) {
          // clone before attempting to push just in case mutable state in the delegate mutates to cause exception
          // part way through. This would indicate some form of bad design, but let's be safe anyway.
          try {
            clones.add(getCloner().cloneObj(document));
          } catch (IOException | ClassNotFoundException e) {
            reportException(document, e, "Failed to clone document when sending to multiple steps");
          }
        }
        // Ok, so we have our copies, now distribute...
        for (Step aNext : next) {
          pushToStep(clones.pop(), aNext);
        }
      }
    } else {
      reportDocStatus(document.getStatus(), document,
          "Document processing for {} terminated ({}) after {}", document.getId(), document.getStatus(), getName());
    }
    log.trace("completing push to next if ok {} for {}", getName(), document.getId());
  }

  private void pushToStep(Document document, Step step) {
    if (step != null) {
      if (this.outputSpace == null) {
        // local processing is our only option, do blocking put.
        try {
          log.trace("starting put ( {} into {} )", getName(), step.getName());
          step.put(document);
          log.trace("completed put ( {} into {} )", getName(), step.getName());
        } catch (InterruptedException e) {
          String message = "Exception while offering to " + step.getName();
          reportException(document, e, message);
        }
      } else {
        log.error("This code path (javaspaces) not yet supported");
        System.err.println("This code path (java spaces) not yet supported");
        System.exit(2);
//      if (this.isFinalHelper)) {
//        // remote processing is our only option.
//        log.debug("todo: send to JavaSpace");
//        // todo: put in JavaSpace
//      } else {
//        // Try to process this item locally first with a non-blocking add, and
//        // if the getNext step is bogged down send it out for processing by helpers.
//        try {
//          step.add(document);
//        } catch (IllegalStateException e) {
//          log.debug("todo: send to JavaSpace");
//          // todo: put in JavaSpace
//        }
//      }
      }
    }
  }

  private void reportDocStatus(Status status, Document document, String message, Object... messageParams) {
    try {
      ThreadContext.put(JesterJAppender.JJ_INGEST_DOCID, document.getId());
      ThreadContext.put(JesterJAppender.JJ_INGEST_SOURCE_SCANNER, document.getSourceScannerName());
      document.setStatus(status);
      log.info(status.getMarker(), message, messageParams);
    } catch (AppenderLoggingException | NoHostAvailableException e) {
      if (Main.isNotShuttingDown()) {
        log.error("Could not contact our internal Cassandra!!!" + e);
      }
    } finally {
      ThreadContext.clearAll();
    }
  }

  @Override
  public void run() {
    try {
      //noinspection InfiniteLoopStatement
      while (true) {
        while (!this.active) {
          log.trace("inactive: {}", getName());
          try {
            Thread.sleep(50);
          } catch (InterruptedException e) {
            // ignore
          }
        }
        log.trace("active: {}", getName());
        ArrayList<Document> temp = null;
        if (peek() != null) {
          temp = new ArrayList<>();
          for (int i = 0; i < queue.size(); i++) {
            temp.add(queue.take());
          }
        }
        if (temp != null) {
          log.trace("{} took {} from queue", getName(), temp.size());
          temp.forEach(new DocumentConsumer());
          continue;
        }

        try {
          // if we don't have work make sure we let others do their work.
          Thread.sleep(5);
        } catch (InterruptedException e) {
          // ignore
        }
      }
    } catch (Throwable t) {
      t.printStackTrace();
      log.error(t);
      System.out.println("Thread for " + getName() + " died. This should not happen and is always a bug in JesterJ " +
          "unless you killed the process with Ctrl-C or similar. This node is Shutting down. If the process was not " +
          "killed, and you got this message during normal running, please open a bug report at http://www.jesterj.org");
      System.exit(2);
    }
  }

  @Override
  public String getName() {
    return stepName;
  }

  protected Logger getLogger() {
    return log;
  }

  @SuppressWarnings("WeakerAccess")
  protected void reportException(Document doc, Exception e, String message) {
    StringWriter buff = new StringWriter();
    e.printStackTrace(new PrintWriter(buff));
    String errorMsg = message + " " + e.getMessage() + "\n" + buff.toString();
    reportDocStatus(Status.ERROR, doc, errorMsg);
  }

  @SuppressWarnings("unused")
  public JavaSpace getInputSpace() {
    return inputSpace;
  }

  public void executeDeferred() {
    deferred.forEach(Runnable::run);
  }

  @Override
  public void addDeferred(Runnable builderAction) {
    deferred.add(builderAction);
  }

  public Cloner<Document> getCloner() {
    return cloner;
  }

  private class DocumentConsumer implements Consumer<Document> {

    @Override
    public void accept(Document document) {
      try {
        log.trace("accepting {}, sending to {} in {}", document.getId(),
            (StepImpl.this.processor == null) ? "null" : StepImpl.this.processor.getName(),
            StepImpl.this.getName());
        Document[] documents = StepImpl.this.processor.processDocument(document);
        log.trace("finished {}, was sent to {} in {}", document.getId(),
            (StepImpl.this.processor == null) ? "null" : StepImpl.this.processor.getName(),
            StepImpl.this.getName());
        for (Document documentResult : documents) {
          pushToNextIfOk(documentResult);
        }
      } catch (Exception e) {
        StepImpl.this.reportException(document, e, e.getMessage());
      }
    }
  }

  public static class Builder extends NamedBuilder<StepImpl> {

    private StepImpl obj;

    public Builder() {
      if (whoAmI() == this.getClass()) {
        obj = new StepImpl();
      }
    }

    private Class whoAmI() {
      return new Object() {
      }.getClass().getEnclosingMethod().getDeclaringClass();
    }

    protected StepImpl getObj() {
      return obj;
    }

    public Builder batchSize(int size) {
      getObj().batchSize = size;
      getObj().queue = new LinkedBlockingQueue<>(size);
      return this;
    }


    public Builder outputSpace(JavaSpace outputSpace) {
      getObj().outputSpace = outputSpace;
      return this;
    }

    public Builder inputSpace(JavaSpace inputSpace) {
      getObj().inputSpace = inputSpace;
      return this;
    }

    public Builder named(String stepName) {
      getObj().stepName = stepName;
      return this;
    }

    public Builder routingBy(ConfiguredBuildable<? extends Router> router) {
      StepImpl currObj = getObj(); // make sure that this cant' change after build() called.
      getObj().addDeferred(() -> currObj.router = router.build());
      return this;
    }

    public Builder withProcessor(ConfiguredBuildable<? extends DocumentProcessor> processor) {
      StepImpl currObj = getObj(); // make sure that this cant' change after build() called.
      getObj().addDeferred(() -> currObj.processor = processor.build());
      return this;
    }

    /**
     * Used when assembling steps into a plan
     *
     * @return the name of the step
     */
    public String getStepName() {
      return getObj().stepName;
    }

    /**
     * Should only be called by a PlanImpl
     *
     * @return the immutable step instance.
     */
    public StepImpl build() {
      StepImpl object = getObj(); // if subclassed we want subclass not our obj. This is intentional
      object.executeDeferred();
      int batchSize = object.batchSize;
      object.queue = new LinkedBlockingQueue<>(batchSize > 0 ? batchSize : 50);
      obj = new StepImpl(); // subclasses such as scanners will mask this with thier own obj field which is ok.
      return object;
    }

    /**
     * should only be used by PlanImpl
     *
     * @param step a fully built step
     */
    void addNextStep(Step step) {
      getObj().nextSteps.put(step.getName(), step);
    }
  }

  @Override
  public String toString() {
    return getName();
  }
}
