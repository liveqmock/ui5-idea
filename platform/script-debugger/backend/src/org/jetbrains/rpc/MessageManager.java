/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.rpc;

import com.intellij.util.containers.ConcurrentIntObjectMap;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Arrays;

/**
 * @param <REQUEST> type of outgoing message
 * @param <INCOMING> type of incoming message
 * @param <INCOMING_WITH_SEQ> type of incoming message that is a command (has sequence number)
 */
public final class MessageManager<REQUEST, INCOMING, INCOMING_WITH_SEQ, SUCCESS, ERROR_DETAILS> extends MessageManagerBase {
  private final ConcurrentIntObjectMap<AsyncResultCallback<SUCCESS, ERROR_DETAILS>> callbackMap = ContainerUtil.createConcurrentIntObjectMap();
  private final Handler<REQUEST, INCOMING, INCOMING_WITH_SEQ, SUCCESS, ERROR_DETAILS> handler;

  public MessageManager(Handler<REQUEST, INCOMING, INCOMING_WITH_SEQ, SUCCESS, ERROR_DETAILS> handler) {
    this.handler = handler;
  }

  public interface Handler<OUTGOING, INCOMING, INCOMING_WITH_SEQ, SUCCESS, ERROR_DETAILS> {
    int getUpdatedSequence(@NotNull OUTGOING message);

    boolean write(@NotNull OUTGOING message) throws IOException;

    INCOMING_WITH_SEQ readIfHasSequence(INCOMING incoming);

    int getSequence(INCOMING_WITH_SEQ incomingWithSeq);

    void acceptNonSequence(INCOMING incoming);

    void call(INCOMING_WITH_SEQ response, AsyncResultCallback<SUCCESS, ERROR_DETAILS> callback);
  }

  public void send(@NotNull REQUEST message, @NotNull AsyncResultCallback<SUCCESS, ERROR_DETAILS> callback) {
    if (rejectIfClosed(callback)) {
      return;
    }

    int sequence = handler.getUpdatedSequence(message);
    callbackMap.put(sequence, callback);
    
    boolean success;
    try {
      success = handler.write(message);
    }
    catch (Throwable e) {
      try {
        failedToSend(sequence);
      }
      finally {
        CommandProcessor.LOG.error("Failed to send", e);
      }
      return;
    }

    if (!success) {
      failedToSend(sequence);
    }
  }

  private void failedToSend(int sequence) {
    AsyncResultCallback<SUCCESS, ERROR_DETAILS> callback = callbackMap.remove(sequence);
    if (callback != null) {
      callback.onError("Failed to send", null);
    }
  }

  public void processIncoming(INCOMING incomingParsed) {
    INCOMING_WITH_SEQ commandResponse = handler.readIfHasSequence(incomingParsed);
    if (commandResponse == null) {
      if (closed) {
        // just ignore
        CommandProcessor.LOG.info("Connection closed, ignore incoming");
      }
      else {
        handler.acceptNonSequence(incomingParsed);
      }
      return;
    }

    AsyncResultCallback<SUCCESS, ERROR_DETAILS> callback = getCallbackAndRemove(handler.getSequence(commandResponse));
    if (rejectIfClosed(callback)) {
      return;
    }

    try {
      handler.call(commandResponse, callback);
    }
    catch (Throwable e) {
      callback.onError("Failed to dispatch response to callback", null);
      CommandProcessor.LOG.error("Failed to dispatch response to callback", e);
    }
  }

  public AsyncResultCallback<SUCCESS, ERROR_DETAILS> getCallbackAndRemove(int id) {
    AsyncResultCallback<SUCCESS, ERROR_DETAILS> callback = callbackMap.remove(id);
    if (callback == null) {
      throw new IllegalArgumentException("Cannot find callback with id " + id);
    }
    return callback;
  }

  public void cancelWaitingRequests() {
    // we should call them in the order they have been submitted
    ConcurrentIntObjectMap<AsyncResultCallback<SUCCESS, ERROR_DETAILS>> map = callbackMap;
    int[] keys = map.keys();
    Arrays.sort(keys);
    for (int key : keys) {
      AsyncResultCallback<SUCCESS, ERROR_DETAILS> callback = map.get(key);
      if (callback != null) {
        rejectCallback(callback);
      }
    }
  }
}