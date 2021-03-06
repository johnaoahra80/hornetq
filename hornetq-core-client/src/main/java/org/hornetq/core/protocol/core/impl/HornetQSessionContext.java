/*
 * Copyright 2005-2014 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.hornetq.core.protocol.core.impl;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import org.hornetq.api.core.HornetQBuffer;
import org.hornetq.api.core.HornetQException;
import org.hornetq.api.core.HornetQExceptionType;
import org.hornetq.api.core.Message;
import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.client.ClientConsumer;
import org.hornetq.api.core.client.ClientSession;
import org.hornetq.api.core.client.SendAcknowledgementHandler;
import org.hornetq.core.client.HornetQClientLogger;
import org.hornetq.core.client.HornetQClientMessageBundle;
import org.hornetq.core.client.impl.AddressQueryImpl;
import org.hornetq.core.client.impl.ClientConsumerImpl;
import org.hornetq.core.client.impl.ClientConsumerInternal;
import org.hornetq.core.client.impl.ClientLargeMessageInternal;
import org.hornetq.core.client.impl.ClientMessageInternal;
import org.hornetq.core.client.impl.ClientProducerCreditsImpl;
import org.hornetq.core.client.impl.ClientSessionImpl;
import org.hornetq.core.message.impl.MessageInternal;
import org.hornetq.core.protocol.core.Channel;
import org.hornetq.core.protocol.core.ChannelHandler;
import org.hornetq.core.protocol.core.CommandConfirmationHandler;
import org.hornetq.core.protocol.core.CoreRemotingConnection;
import org.hornetq.core.protocol.core.Packet;
import org.hornetq.core.protocol.core.impl.wireformat.CreateQueueMessage;
import org.hornetq.core.protocol.core.impl.wireformat.CreateSessionMessage;
import org.hornetq.core.protocol.core.impl.wireformat.CreateSharedQueueMessage;
import org.hornetq.core.protocol.core.impl.wireformat.DisconnectConsumerMessage;
import org.hornetq.core.protocol.core.impl.wireformat.HornetQExceptionMessage;
import org.hornetq.core.protocol.core.impl.wireformat.ReattachSessionMessage;
import org.hornetq.core.protocol.core.impl.wireformat.ReattachSessionResponseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.RollbackMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionAcknowledgeMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionAddMetaDataMessageV2;
import org.hornetq.core.protocol.core.impl.wireformat.SessionBindingQueryMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionBindingQueryResponseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionCloseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionConsumerCloseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionConsumerFlowCreditMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionCreateConsumerMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionDeleteQueueMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionExpireMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionForceConsumerDelivery;
import org.hornetq.core.protocol.core.impl.wireformat.SessionIndividualAcknowledgeMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionProducerCreditsFailMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionProducerCreditsMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionQueueQueryMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionQueueQueryResponseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionReceiveContinuationMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionReceiveLargeMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionReceiveMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionRequestProducerCreditsMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionSendContinuationMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionSendLargeMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionSendMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionUniqueAddMetaDataMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXAAfterFailedMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXACommitMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXAEndMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXAForgetMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXAGetInDoubtXidsResponseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXAGetTimeoutResponseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXAJoinMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXAPrepareMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXAResponseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXAResumeMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXARollbackMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXASetTimeoutMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXASetTimeoutResponseMessage;
import org.hornetq.core.protocol.core.impl.wireformat.SessionXAStartMessage;
import org.hornetq.spi.core.protocol.RemotingConnection;
import org.hornetq.spi.core.remoting.Connection;
import org.hornetq.spi.core.remoting.SessionContext;
import org.hornetq.utils.TokenBucketLimiterImpl;

import static org.hornetq.core.protocol.core.impl.PacketImpl.DISCONNECT_CONSUMER;
import static org.hornetq.core.protocol.core.impl.PacketImpl.EXCEPTION;
import static org.hornetq.core.protocol.core.impl.PacketImpl.SESS_RECEIVE_CONTINUATION;
import static org.hornetq.core.protocol.core.impl.PacketImpl.SESS_RECEIVE_LARGE_MSG;
import static org.hornetq.core.protocol.core.impl.PacketImpl.SESS_RECEIVE_MSG;

/**
 * @author Clebert Suconic
 */

public class HornetQSessionContext extends SessionContext
{
   private final Channel sessionChannel;
   private final int serverVersion;
   private int confirmationWindow;
   private final String name;


   public HornetQSessionContext(String name, RemotingConnection remotingConnection, Channel sessionChannel, int serverVersion, int confirmationWindow)
   {
      super(remotingConnection);

      this.name = name;
      this.sessionChannel = sessionChannel;
      this.serverVersion = serverVersion;
      this.confirmationWindow = confirmationWindow;

      ChannelHandler handler = new ClientSessionPacketHandler();
      sessionChannel.setHandler(handler);


      if (confirmationWindow >= 0)
      {
         sessionChannel.setCommandConfirmationHandler(confirmationHandler);
      }
   }


   private final CommandConfirmationHandler confirmationHandler = new CommandConfirmationHandler()
   {
      public void commandConfirmed(final Packet packet)
      {
         if (packet.getType() == PacketImpl.SESS_SEND)
         {
            SessionSendMessage ssm = (SessionSendMessage) packet;
            callSendAck(ssm.getHandler(), ssm.getMessage());
         }
         else if (packet.getType() == PacketImpl.SESS_SEND_CONTINUATION)
         {
            SessionSendContinuationMessage scm = (SessionSendContinuationMessage) packet;
            if (!scm.isContinues())
            {
               callSendAck(scm.getHandler(), scm.getMessage());
            }
         }
      }

      private void callSendAck(SendAcknowledgementHandler handler, final Message message)
      {
         if (handler != null)
         {
            handler.sendAcknowledged(message);
         }
         else if (sendAckHandler != null)
         {
            sendAckHandler.sendAcknowledged(message);
         }
      }

   };


   // Failover utility methods

   @Override
   public void returnBlocking(HornetQException cause)
   {
      sessionChannel.returnBlocking(cause);
   }

   @Override
   public void lockCommunications()
   {
      sessionChannel.lock();
   }

   @Override
   public void releaseCommunications()
   {
      sessionChannel.setTransferring(false);
      sessionChannel.unlock();
   }

   public void cleanup()
   {
      sessionChannel.close();

      // if the server is sending a disconnect
      // any pending blocked operation could hang without this
      sessionChannel.returnBlocking();
   }

   @Override
   public void linkFlowControl(SimpleString address, ClientProducerCreditsImpl clientProducerCredits)
   {
      // nothing to be done here... Flow control here is done on the core side
   }


   public void setSendAcknowledgementHandler(final SendAcknowledgementHandler handler)
   {
      sessionChannel.setCommandConfirmationHandler(confirmationHandler);
      this.sendAckHandler = handler;
   }

   public void createSharedQueue(SimpleString address,
                                 SimpleString queueName,
                                 SimpleString filterString,
                                 boolean durable) throws HornetQException
   {
      sessionChannel.sendBlocking(new CreateSharedQueueMessage(address, queueName, filterString, durable, true), PacketImpl.NULL_RESPONSE);
   }

   public void deleteQueue(final SimpleString queueName) throws HornetQException
   {
      sessionChannel.sendBlocking(new SessionDeleteQueueMessage(queueName), PacketImpl.NULL_RESPONSE);
   }

   public ClientSession.QueueQuery queueQuery(final SimpleString queueName) throws HornetQException
   {
      SessionQueueQueryMessage request = new SessionQueueQueryMessage(queueName);
      SessionQueueQueryResponseMessage response = (SessionQueueQueryResponseMessage) sessionChannel.sendBlocking(request, PacketImpl.SESS_QUEUEQUERY_RESP);

      return response.toQueueQuery();

   }


   public ClientConsumerInternal createConsumer(SimpleString queueName, SimpleString filterString,
                                                int windowSize, int maxRate, int ackBatchSize, boolean browseOnly,
                                                Executor executor, Executor flowControlExecutor) throws HornetQException
   {
      long consumerID = idGenerator.generateID();

      HornetQConsumerContext consumerContext = new HornetQConsumerContext(consumerID);

      SessionCreateConsumerMessage request = new SessionCreateConsumerMessage(consumerID,
                                                                              queueName,
                                                                              filterString,
                                                                              browseOnly,
                                                                              true);

      SessionQueueQueryResponseMessage queueInfo = (SessionQueueQueryResponseMessage) sessionChannel.sendBlocking(request, PacketImpl.SESS_QUEUEQUERY_RESP);

      // The actual windows size that gets used is determined by the user since
      // could be overridden on the queue settings
      // The value we send is just a hint

      return new ClientConsumerImpl(session,
                                    consumerContext,
                                    queueName,
                                    filterString,
                                    browseOnly,
                                    calcWindowSize(windowSize),
                                    ackBatchSize,
                                    maxRate > 0 ? new TokenBucketLimiterImpl(maxRate,
                                                                             false)
                                       : null,
                                    executor,
                                    flowControlExecutor,
                                    this,
                                    queueInfo.toQueueQuery(),
                                    lookupTCCL());
   }


   public int getServerVersion()
   {
      return serverVersion;
   }

   public ClientSession.AddressQuery addressQuery(final SimpleString address) throws HornetQException
   {
      SessionBindingQueryResponseMessage response =
         (SessionBindingQueryResponseMessage) sessionChannel.sendBlocking(new SessionBindingQueryMessage(address), PacketImpl.SESS_BINDINGQUERY_RESP);

      return new AddressQueryImpl(response.isExists(), response.getQueueNames());
   }


   @Override
   public void closeConsumer(final ClientConsumer consumer) throws HornetQException
   {
      sessionChannel.sendBlocking(new SessionConsumerCloseMessage(getConsumerID(consumer)), PacketImpl.NULL_RESPONSE);
   }

   public void sendConsumerCredits(final ClientConsumer consumer, final int credits)
   {
      sessionChannel.send(new SessionConsumerFlowCreditMessage(getConsumerID(consumer), credits));
   }

   public void forceDelivery(final ClientConsumer consumer, final long sequence) throws HornetQException
   {
      SessionForceConsumerDelivery request = new SessionForceConsumerDelivery(getConsumerID(consumer), sequence);
      sessionChannel.send(request);
   }

   public void simpleCommit() throws HornetQException
   {
      sessionChannel.sendBlocking(new PacketImpl(PacketImpl.SESS_COMMIT), PacketImpl.NULL_RESPONSE);
   }

   public void simpleRollback(boolean lastMessageAsDelivered) throws HornetQException
   {
      sessionChannel.sendBlocking(new RollbackMessage(lastMessageAsDelivered), PacketImpl.NULL_RESPONSE);
   }

   public void sessionStart() throws HornetQException
   {
      sessionChannel.send(new PacketImpl(PacketImpl.SESS_START));
   }

   public void sessionStop() throws HornetQException
   {
      sessionChannel.sendBlocking(new PacketImpl(PacketImpl.SESS_STOP), PacketImpl.NULL_RESPONSE);
   }

   public void addSessionMetadata(String key, String data) throws HornetQException
   {
      sessionChannel.sendBlocking(new SessionAddMetaDataMessageV2(key, data), PacketImpl.NULL_RESPONSE);
   }


   public void addUniqueMetaData(String key, String data) throws HornetQException
   {
      sessionChannel.sendBlocking(new SessionUniqueAddMetaDataMessage(key, data), PacketImpl.NULL_RESPONSE);
   }

   public void xaCommit(Xid xid, boolean onePhase) throws XAException, HornetQException
   {
      SessionXACommitMessage packet = new SessionXACommitMessage(xid, onePhase);
      SessionXAResponseMessage response = (SessionXAResponseMessage) sessionChannel.sendBlocking(packet, PacketImpl.SESS_XA_RESP);

      if (response.isError())
      {
         throw new XAException(response.getResponseCode());
      }

      if (HornetQClientLogger.LOGGER.isTraceEnabled())
      {
         HornetQClientLogger.LOGGER.trace("finished commit on " + ClientSessionImpl.convert(xid) + " with response = " + response);
      }
   }

   public void xaEnd(Xid xid, int flags) throws XAException, HornetQException
   {
      Packet packet;
      if (flags == XAResource.TMSUSPEND)
      {
         packet = new PacketImpl(PacketImpl.SESS_XA_SUSPEND);
      }
      else if (flags == XAResource.TMSUCCESS)
      {
         packet = new SessionXAEndMessage(xid, false);
      }
      else if (flags == XAResource.TMFAIL)
      {
         packet = new SessionXAEndMessage(xid, true);
      }
      else
      {
         throw new XAException(XAException.XAER_INVAL);
      }

      SessionXAResponseMessage response = (SessionXAResponseMessage) sessionChannel.sendBlocking(packet, PacketImpl.SESS_XA_RESP);

      if (response.isError())
      {
         throw new XAException(response.getResponseCode());
      }
   }


   public void sendProducerCreditsMessage(final int credits, final SimpleString address)
   {
      sessionChannel.send(new SessionRequestProducerCreditsMessage(credits, address));
   }

   /**
    * HornetQ does support large messages
    *
    * @return
    */
   public boolean supportsLargeMessage()
   {
      return true;
   }

   @Override
   public int getCreditsOnSendingFull(MessageInternal msgI)
   {
      return msgI.getEncodeSize();
   }

   public void sendFullMessage(MessageInternal msgI, boolean sendBlocking, SendAcknowledgementHandler handler, SimpleString defaultAddress) throws HornetQException
   {
      SessionSendMessage packet = new SessionSendMessage(msgI, sendBlocking, handler);

      if (sendBlocking)
      {
         sessionChannel.sendBlocking(packet, PacketImpl.NULL_RESPONSE);
      }
      else
      {
         sessionChannel.sendBatched(packet);
      }
   }

   @Override
   public int sendInitialChunkOnLargeMessage(MessageInternal msgI) throws HornetQException
   {
      SessionSendLargeMessage initialChunk = new SessionSendLargeMessage(msgI);

      sessionChannel.send(initialChunk);

      return msgI.getHeadersAndPropertiesEncodeSize();
   }

   @Override
   public int sendLargeMessageChunk(MessageInternal msgI, long messageBodySize, boolean sendBlocking, boolean lastChunk, byte[] chunk, SendAcknowledgementHandler messageHandler) throws HornetQException
   {
      final boolean requiresResponse = lastChunk && sendBlocking;
      final SessionSendContinuationMessage chunkPacket =
         new SessionSendContinuationMessage(msgI, chunk, !lastChunk,
                                            requiresResponse, messageBodySize, messageHandler);

      if (requiresResponse)
      {
         // When sending it blocking, only the last chunk will be blocking.
         sessionChannel.sendBlocking(chunkPacket, PacketImpl.NULL_RESPONSE);
      }
      else
      {
         sessionChannel.send(chunkPacket);
      }

      return chunkPacket.getPacketSize();
   }

   public void sendACK(boolean individual, boolean block, final ClientConsumer consumer, final Message message) throws HornetQException
   {
      PacketImpl messagePacket;
      if (individual)
      {
         messagePacket = new SessionIndividualAcknowledgeMessage(getConsumerID(consumer), message.getMessageID(), block);
      }
      else
      {
         messagePacket = new SessionAcknowledgeMessage(getConsumerID(consumer), message.getMessageID(), block);
      }

      if (block)
      {
         sessionChannel.sendBlocking(messagePacket, PacketImpl.NULL_RESPONSE);
      }
      else
      {
         sessionChannel.sendBatched(messagePacket);
      }
   }

   public void expireMessage(final ClientConsumer consumer, Message message) throws HornetQException
   {
      SessionExpireMessage messagePacket = new SessionExpireMessage(getConsumerID(consumer), message.getMessageID());

      sessionChannel.send(messagePacket);
   }


   public void sessionClose() throws HornetQException
   {
      sessionChannel.sendBlocking(new SessionCloseMessage(), PacketImpl.NULL_RESPONSE);
   }

   public void xaForget(Xid xid) throws XAException, HornetQException
   {
      SessionXAResponseMessage response = (SessionXAResponseMessage) sessionChannel.sendBlocking(new SessionXAForgetMessage(xid), PacketImpl.SESS_XA_RESP);

      if (response.isError())
      {
         throw new XAException(response.getResponseCode());
      }
   }

   public int xaPrepare(Xid xid) throws XAException, HornetQException
   {
      SessionXAPrepareMessage packet = new SessionXAPrepareMessage(xid);

      SessionXAResponseMessage response = (SessionXAResponseMessage) sessionChannel.sendBlocking(packet, PacketImpl.SESS_XA_RESP);

      if (response.isError())
      {
         throw new XAException(response.getResponseCode());
      }
      else
      {
         return response.getResponseCode();
      }
   }

   public Xid[] xaScan() throws HornetQException
   {
      SessionXAGetInDoubtXidsResponseMessage response = (SessionXAGetInDoubtXidsResponseMessage) sessionChannel.sendBlocking(new PacketImpl(PacketImpl.SESS_XA_INDOUBT_XIDS), PacketImpl.SESS_XA_INDOUBT_XIDS_RESP);

      List<Xid> xids = response.getXids();

      Xid[] xidArray = xids.toArray(new Xid[xids.size()]);

      return xidArray;
   }

   public void xaRollback(Xid xid, boolean wasStarted) throws HornetQException, XAException
   {
      SessionXARollbackMessage packet = new SessionXARollbackMessage(xid);

      SessionXAResponseMessage response = (SessionXAResponseMessage) sessionChannel.sendBlocking(packet, PacketImpl.SESS_XA_RESP);

      if (response.isError())
      {
         throw new XAException(response.getResponseCode());
      }
   }

   public void xaStart(Xid xid, int flags) throws XAException, HornetQException
   {
      Packet packet;
      if (flags == XAResource.TMJOIN)
      {
         packet = new SessionXAJoinMessage(xid);
      }
      else if (flags == XAResource.TMRESUME)
      {
         packet = new SessionXAResumeMessage(xid);
      }
      else if (flags == XAResource.TMNOFLAGS)
      {
         // Don't need to flush since the previous end will have done this
         packet = new SessionXAStartMessage(xid);
      }
      else
      {
         throw new XAException(XAException.XAER_INVAL);
      }

      SessionXAResponseMessage response = (SessionXAResponseMessage) sessionChannel.sendBlocking(packet, PacketImpl.SESS_XA_RESP);

      if (response.isError())
      {
         HornetQClientLogger.LOGGER.errorCallingStart(response.getMessage(), response.getResponseCode());
         throw new XAException(response.getResponseCode());
      }
   }

   public boolean configureTransactionTimeout(int seconds) throws HornetQException
   {
      SessionXASetTimeoutResponseMessage response = (SessionXASetTimeoutResponseMessage) sessionChannel.sendBlocking(new SessionXASetTimeoutMessage(seconds), PacketImpl.SESS_XA_SET_TIMEOUT_RESP);

      return response.isOK();
   }

   public int recoverSessionTimeout() throws HornetQException
   {
      SessionXAGetTimeoutResponseMessage response = (SessionXAGetTimeoutResponseMessage) sessionChannel.sendBlocking(new PacketImpl(PacketImpl.SESS_XA_GET_TIMEOUT), PacketImpl.SESS_XA_GET_TIMEOUT_RESP);

      return response.getTimeoutSeconds();
   }

   public void createQueue(SimpleString address, SimpleString queueName, SimpleString filterString, boolean durable, boolean temp) throws HornetQException
   {
      CreateQueueMessage request = new CreateQueueMessage(address, queueName, filterString, durable, temp, true);
      sessionChannel.sendBlocking(request, PacketImpl.NULL_RESPONSE);
   }

   @Override
   public boolean reattachOnNewConnection(RemotingConnection newConnection) throws HornetQException
   {

      this.remotingConnection = newConnection;

      sessionChannel.transferConnection((CoreRemotingConnection) newConnection);

      Packet request = new ReattachSessionMessage(name, sessionChannel.getLastConfirmedCommandID());

      Channel channel1 = getCoreConnection().getChannel(1, -1);

      ReattachSessionResponseMessage response = (ReattachSessionResponseMessage) channel1.sendBlocking(request, PacketImpl.REATTACH_SESSION_RESP);

      if (response.isReattached())
      {
         if (HornetQClientLogger.LOGGER.isDebugEnabled())
         {
            HornetQClientLogger.LOGGER.debug("ClientSession reattached fine, replaying commands");
         }
         // The session was found on the server - we reattached transparently ok

         sessionChannel.replayCommands(response.getLastConfirmedCommandID());

         return true;
      }
      else
      {

         sessionChannel.clearCommands();

         return false;
      }

   }

   public void recreateSession(final String username,
                               final String password,
                               final int minLargeMessageSize,
                               final boolean xa,
                               final boolean autoCommitSends,
                               final boolean autoCommitAcks,
                               final boolean preAcknowledge,
                               final SimpleString defaultAddress) throws HornetQException
   {
      Packet createRequest = new CreateSessionMessage(name,
                                                      sessionChannel.getID(),
                                                      getServerVersion(),
                                                      username,
                                                      password,
                                                      minLargeMessageSize,
                                                      xa,
                                                      autoCommitSends,
                                                      autoCommitAcks,
                                                      preAcknowledge,
                                                      confirmationWindow,
                                                      defaultAddress == null ? null
                                                         : defaultAddress.toString());
      boolean retry;
      do
      {
         try
         {
            getCreateChannel().sendBlocking(createRequest, PacketImpl.CREATESESSION_RESP);
            retry = false;
         }
         catch (HornetQException e)
         {
            // the session was created while its server was starting, retry it:
            if (e.getType() == HornetQExceptionType.SESSION_CREATION_REJECTED)
            {
               HornetQClientLogger.LOGGER.retryCreateSessionSeverStarting(name);
               retry = true;
               // sleep a little bit to avoid spinning too much
               try
               {
                  Thread.sleep(10);
               }
               catch (InterruptedException ie)
               {
                  Thread.currentThread().interrupt();
                  throw e;
               }
            }
            else
            {
               throw e;
            }
         }
      }
      while (retry && !session.isClosing());
   }

   @Override
   public void recreateConsumerOnServer(ClientConsumerInternal consumerInternal) throws HornetQException
   {
      ClientSession.QueueQuery queueInfo = consumerInternal.getQueueInfo();

      // We try and recreate any non durable queues, since they probably won't be there unless
      // they are defined in hornetq-configuration.xml
      // This allows e.g. JMS non durable subs and temporary queues to continue to be used after failover
      if (!queueInfo.isDurable())
      {
         CreateQueueMessage createQueueRequest = new CreateQueueMessage(queueInfo.getAddress(),
                                                                        queueInfo.getName(),
                                                                        queueInfo.getFilterString(),
                                                                        false,
                                                                        queueInfo.isTemporary(),
                                                                        false);

         sendPacketWithoutLock(sessionChannel, createQueueRequest);
      }

      SessionCreateConsumerMessage createConsumerRequest = new SessionCreateConsumerMessage(getConsumerID(consumerInternal),
                                                                                            consumerInternal.getQueueName(),
                                                                                            consumerInternal.getFilterString(),
                                                                                            consumerInternal.isBrowseOnly(),
                                                                                            false);

      sendPacketWithoutLock(sessionChannel, createConsumerRequest);

      int clientWindowSize = consumerInternal.getClientWindowSize();

      if (clientWindowSize != 0)
      {
         SessionConsumerFlowCreditMessage packet = new SessionConsumerFlowCreditMessage(getConsumerID(consumerInternal),
                                                                                        clientWindowSize);

         sendPacketWithoutLock(sessionChannel, packet);
      }
      else
      {
         // https://jira.jboss.org/browse/HORNETQ-522
         SessionConsumerFlowCreditMessage packet = new SessionConsumerFlowCreditMessage(getConsumerID(consumerInternal),
                                                                                        1);
         sendPacketWithoutLock(sessionChannel, packet);
      }
   }

   public void xaFailed(Xid xid) throws HornetQException
   {
      sendPacketWithoutLock(sessionChannel, new SessionXAAfterFailedMessage(xid));
   }

   public void restartSession() throws HornetQException
   {
      sendPacketWithoutLock(sessionChannel, new PacketImpl(PacketImpl.SESS_START));
   }

   @Override
   public void resetMetadata(HashMap<String, String> metaDataToSend)
   {
      // Resetting the metadata after failover
      for (Map.Entry<String, String> entries : metaDataToSend.entrySet())
      {
         sendPacketWithoutLock(sessionChannel, new SessionAddMetaDataMessageV2(entries.getKey(), entries.getValue(), false));
      }
   }


   private Channel getCreateChannel()
   {
      return getCoreConnection().getChannel(1, -1);
   }

   private CoreRemotingConnection getCoreConnection()
   {
      return (CoreRemotingConnection) remotingConnection;
   }


   /**
    * This doesn't apply to other protocols probably, so it will be a hornetq exclusive feature
    *
    * @throws HornetQException
    */
   private void handleConsumerDisconnected(DisconnectConsumerMessage packet) throws HornetQException
   {
      DisconnectConsumerMessage message = packet;

      session.handleConsumerDisconnect(new HornetQConsumerContext(message.getConsumerId()));
   }

   private void handleReceivedMessagePacket(SessionReceiveMessage messagePacket) throws Exception
   {
      ClientMessageInternal msgi = (ClientMessageInternal) messagePacket.getMessage();

      msgi.setDeliveryCount(messagePacket.getDeliveryCount());

      msgi.setFlowControlSize(messagePacket.getPacketSize());

      handleReceiveMessage(new HornetQConsumerContext(messagePacket.getConsumerID()), msgi);
   }

   private void handleReceiveLargeMessage(SessionReceiveLargeMessage serverPacket) throws Exception
   {
      ClientLargeMessageInternal clientLargeMessage = (ClientLargeMessageInternal) serverPacket.getLargeMessage();

      clientLargeMessage.setFlowControlSize(serverPacket.getPacketSize());

      clientLargeMessage.setDeliveryCount(serverPacket.getDeliveryCount());

      handleReceiveLargeMessage(new HornetQConsumerContext(serverPacket.getConsumerID()), clientLargeMessage, serverPacket.getLargeMessageSize());
   }


   private void handleReceiveContinuation(SessionReceiveContinuationMessage continuationPacket) throws Exception
   {
      handleReceiveContinuation(new HornetQConsumerContext(continuationPacket.getConsumerID()), continuationPacket.getBody(), continuationPacket.getPacketSize(),
                                continuationPacket.isContinues());
   }


   protected void handleReceiveProducerCredits(SessionProducerCreditsMessage message)
   {
      handleReceiveProducerCredits(message.getAddress(), message.getCredits());
   }


   protected void handleReceiveProducerFailCredits(SessionProducerCreditsFailMessage message)
   {
      handleReceiveProducerFailCredits(message.getAddress(), message.getCredits());
   }

   class ClientSessionPacketHandler implements ChannelHandler
   {

      public void handlePacket(final Packet packet)
      {
         byte type = packet.getType();

         try
         {
            switch (type)
            {
               case DISCONNECT_CONSUMER:
               {
                  handleConsumerDisconnected((DisconnectConsumerMessage) packet);
                  break;
               }
               case SESS_RECEIVE_CONTINUATION:
               {
                  handleReceiveContinuation((SessionReceiveContinuationMessage) packet);

                  break;
               }
               case SESS_RECEIVE_MSG:
               {
                  handleReceivedMessagePacket((SessionReceiveMessage) packet);

                  break;
               }
               case SESS_RECEIVE_LARGE_MSG:
               {
                  handleReceiveLargeMessage((SessionReceiveLargeMessage) packet);

                  break;
               }
               case PacketImpl.SESS_PRODUCER_CREDITS:
               {
                  handleReceiveProducerCredits((SessionProducerCreditsMessage) packet);

                  break;
               }
               case PacketImpl.SESS_PRODUCER_FAIL_CREDITS:
               {
                  handleReceiveProducerFailCredits((SessionProducerCreditsFailMessage) packet);

                  break;
               }
               case EXCEPTION:
               {
                  // We can only log these exceptions
                  // maybe we should cache it on SessionContext and throw an exception on any next calls
                  HornetQExceptionMessage mem = (HornetQExceptionMessage) packet;

                  HornetQClientLogger.LOGGER.receivedExceptionAsynchronously(mem.getException());

                  break;
               }
               default:
               {
                  throw new IllegalStateException("Invalid packet: " + type);
               }
            }
         }
         catch (Exception e)
         {
            HornetQClientLogger.LOGGER.failedToHandlePacket(e);
         }

         sessionChannel.confirm(packet);
      }
   }

   private long getConsumerID(ClientConsumer consumer)
   {
      return ((HornetQConsumerContext)consumer.getConsumerContext()).getId();
   }

   private ClassLoader lookupTCCL()
   {
      return AccessController.doPrivileged(new PrivilegedAction<ClassLoader>()
      {
         public ClassLoader run()
         {
            return Thread.currentThread().getContextClassLoader();
         }
      });

   }

   private int calcWindowSize(final int windowSize)
   {
      int clientWindowSize;
      if (windowSize == -1)
      {
         // No flow control - buffer can increase without bound! Only use with
         // caution for very fast consumers
         clientWindowSize = -1;
      }
      else if (windowSize == 0)
      {
         // Slow consumer - no buffering
         clientWindowSize = 0;
      }
      else if (windowSize == 1)
      {
         // Slow consumer = buffer 1
         clientWindowSize = 1;
      }
      else if (windowSize > 1)
      {
         // Client window size is half server window size
         clientWindowSize = windowSize >> 1;
      }
      else
      {
         throw HornetQClientMessageBundle.BUNDLE.invalidWindowSize(windowSize);
      }

      return clientWindowSize;
   }


   private void sendPacketWithoutLock(final Channel parameterChannel, final Packet packet)
   {
      packet.setChannelID(parameterChannel.getID());

      Connection conn = parameterChannel.getConnection().getTransportConnection();

      HornetQBuffer buffer = packet.encode(this.getCoreConnection());

      conn.write(buffer, false, false);
   }


}
