'''
Created on Jul 15, 2009

@author: Tonio Gsell
@author: Mustafa Yuecel
'''

import socket
import struct
import time
import logging
import Queue
from threading import Thread, Event, Lock

import BackLogMessage

# Ping request interval in seconds.
PING_INTERVAL_SEC = 10.0

# Time in seconds in which at least one ping acknowledge
# message should have been received. If no acknowledge has
# been received, the connection is considered broken.
PING_ACK_CHECK_INTERVAL_SEC = 60.0

SEND_QUEUE_SIZE = 25

STUFFING_BYTE = 0x7e
HELLO_BYTE = 0x7d

SOL_IP = 0
IP_MTU = 14

class GSNPeerClass(Thread):
    '''
    Offers the server functionality for GSN.
    '''
    
    '''
    data/instance attributes:
    _logger
    _parent
    _deviceid
    _port
    _serversocket
    _pingtimer
    _pingwatchdog
    _connected
    _inCounter
    _outCounter
    _connectionLosses
    _backlogCounter
    _work
    _stopped
    '''

    def __init__(self, parent, deviceid, port):
        '''
        Inititalizes the GSN server.
        
        @param parent: the BackLogMain object
        @param port: the local port the server should be listening on
        
        @raise Exception: if there is a problem opening the server socket
        '''
        Thread.__init__(self)
        
        self._logger = logging.getLogger(self.__class__.__name__)
        
        # initialize variables
        self._parent = parent
        self._deviceid = deviceid
        self._port = port

        self._inCounter = 0
        self._outCounter = 0
        self._connectionLosses = 0
        self._backlogCounter = 0
        self._connected = False
        self._stopped = False
        self._work = Event()
        
        # try to open a server socket to which GSN can connect to
        try:
            self._serversocket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self._serversocket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self._serversocket.bind(('0.0.0.0', port))
            self._serversocket.listen(1)
        except Exception, e:
            raise TypeError(e.__str__())

        self._pingtimer = PingTimer(PING_INTERVAL_SEC, self.ping)
        self._pingwatchdog = PingWatchDog(PING_ACK_CHECK_INTERVAL_SEC, self.watchdogdisconnect)
        
        
    def run(self):
        self._logger.info('started')
        
        self._pingwatchdog.start()
        self._pingtimer.start()
        self._work.set()
            
        while not self._stopped:
            self._work.wait()
            if self._stopped:
                break
            self._work.clear()
        
            self._gsnlistener = GSNListener(self, self._port, self._serversocket)
            if not self._stopped:
                self._gsnlistener.start()
 
        self._logger.info('died')


    def stop(self):
        self._pingwatchdog.stop()
        self._pingtimer.stop()
        self._gsnlistener.stop()
        self._stopped = True
        self._work.set()
        self._serversocket.shutdown(socket.SHUT_RDWR)
        self._serversocket.close()
        self._logger.info('stopped')


    def getStatus(self):
        return (self._inCounter, self._outCounter, self._backlogCounter, self._connectionLosses)
            
            
    def isConnected(self):
        return self._connected


    def sendToGSN(self, msg, priority, resend=False):
        '''
        Send message to GSN.
        
        @param blMessage: the BackLogMessage to be sent to GSN
        
        @return: True if the message could have been sent to GSN otherwise False
        '''        
        self._outCounter += 1
        if resend:
            return self._gsnlistener._gsnwriter.addResendMsg(msg, priority)        
        else:
            return self._gsnlistener._gsnwriter.addMsg(msg, priority)
        
        
    def pktReceived(self, pkt):
        # convert the packet to a BackLogMessage
        msg = BackLogMessage.BackLogMessageClass()
        msg.setMessage(pkt)
        # get the message type
        msgType = msg.getType()
        
        self._logger.debug('rcv (%d,%d,%d)' % (msgType, msg.getTimestamp(), len(pkt)))
        self._inCounter += 1
        
        # is it an answer to a ping?
        if msgType == BackLogMessage.PING_ACK_MESSAGE_TYPE:
            self._pingwatchdog.reset()
        # or is it a ping request
        elif msgType == BackLogMessage.PING_MESSAGE_TYPE:
            # answer with a ping ack
            self.pingAck(msg.getTimestamp())
        elif msgType == BackLogMessage.ACK_MESSAGE_TYPE:
            # if it is an acknowledge, tell BackLogMain to have received one
            self._parent.ackReceived(msg.getTimestamp())
        else:
            # send the packet to all plugins which 'use' this message type
            msgTypeValid = False
            for plug in self._parent.plugins:
                if msgType == plug[1].getMsgType():
                    plug[1].msgReceived(msg.getPayload())
                    msgTypeValid = True
                    break
            if msgTypeValid == False:
                self.error('unknown message type ' + str(msgType) + ' received')


    def disconnect(self):
        self._logger.debug('disconnect')
        self._connected = False
        self._connectionLosses += 1
        self._parent.connectionToGSNlost()
        self._pingwatchdog.pause()
        self._pingtimer.pause()
        self._work.set()
        
        
    def watchdogdisconnect(self):
        self._gsnlistener.disconnect()
        
        
    def connected(self):
        self._connected = True
        self._parent.connectionToGSNestablished()
        self._pingtimer.resume()
        self._pingwatchdog.resume()


    def ping(self):
        self.sendToGSN(BackLogMessage.BackLogMessageClass(BackLogMessage.PING_MESSAGE_TYPE, int(time.time()*1000)), 0)


    def pingAck(self, timestamp):
        self.sendToGSN(BackLogMessage.BackLogMessageClass(BackLogMessage.PING_ACK_MESSAGE_TYPE, timestamp), 0)


    def processMsg(self, msgType, timestamp, payload, priority, backlog=False):
        '''
        Store the message in the backlog database if needed and try to send
        it to GSN.
        
        Send the message using the GSNServer class.
        This function should be used by the plugins to send any data to GSN.
        
        @param msgType: the message type. The message type must be listed in BackLogMessage.
        @param timestamp: the timestamp this message has been generated
        @param payload: the raw data to be sent (no more than 4 Gb)
        @param backLog: True if this message should be backlogged in the database, otherwise False.
                        BackLogMessageside has to send an acknowledge to remove this message from
                        the backlog database after successful processing if set to True.
        @param backup: True if this message should be stored in the backup database, otherwise False.
                       The message will only be stored in the backup database, if this is set to
                       True AND BackLogMain has been started with the '--backup' option.
                       
        @return: True if the message has been stored successfully into the backlog database if needed,
                 otherwise False.
        '''
        ret = True
        
        if backlog:
            self._backlogCounter += 1
            # back log the message
            ret = self._parent.backlog.storeMsg(timestamp, msgType, payload)
            
        # send the message to the GSN backend
        self.sendToGSN(BackLogMessage.BackLogMessageClass(msgType, timestamp, payload), priority)
                
        return ret


    def processResendMsg(self, msgType, timestamp, payload):
        return self.sendToGSN(BackLogMessage.BackLogMessageClass(msgType, timestamp, payload), 99, True)


    def error(self, msg):
        self._parent.incrementErrorCounter()
        self._logger.error(msg)
    
    
    
    
class GSNListener(Thread):
    '''
    Offers the listener functionality for GSN.
    '''
    
    '''
    data/instance attributes:
    _logger
    _parent
    _port
    _serversocket
    _gsnwriter
    clientsocket
    _connected
    _clientaddr
    _lock
    _stopped
    _stuff
    _stuffread
    '''

    def __init__(self, parent, port, serversocket):
        '''
        Inititalizes the GSN server.
        
        @param parent: the BackLogMain object
        @param port: the local port the server should be listening on
        
        @raise Exception: if there is a problem opening the server socket
        '''
        Thread.__init__(self)
        
        self._logger = logging.getLogger(self.__class__.__name__)
        
        # initialize variables
        self._parent = parent
        self._port = port
        self._serversocket = serversocket
        self._stuff = False
        
        self._gsnwriter = GSNWriter(self)

        self.clientsocket = None
        self._clientaddr = None
        self._stuffread = ''

        self._connected = False
        self._lock = Lock()
        self._stopped = False


    def run(self):
        self._logger.info('started')
        # thread is waiting for the first resume to continue
        self._gsnwriter.start()
        
        pkt_len = None
        pkt = None
        msgType = None
        msgTypeValid = None
        connecting = True
        
        # listen for a connection request by a GSN instance (this is blocking)
        self._logger.info('listening on port ' + str(self._port))
        try:
            (self.clientsocket, self._clientaddr) = self._serversocket.accept()
            if self._stopped:
                self._logger.info('died')
                return
            self._connected = True
            self._gsnwriter.sendHelloMsg()
        except socket.error, e:
            if not self._stopped:
                self.error('exception while accepting connection: ' + e.__str__())
                self.disconnect()
            self._logger.info('died')
            return

        try:
            self._logger.info('got connection from ' + str(self._clientaddr))

            self.clientsocket.settimeout(None)

            # let BackLogMain know that GSN successfully connected
            self._parent._parent.backlog.resend(True)

            while not self._stopped:
                self._logger.debug('rcv...')
                
                if connecting:
                    try:
                        helloByte = self.pktReadAndDestuff(1)
                        if not helloByte:
                            continue
                    
                        if len(helloByte) != 1:
                            raise IOError('packet length does not match')
                    except (IOError, socket.error), e:
                        if not self._stopped:
                            raise
                        break
                    
                    if ord(helloByte) != HELLO_BYTE:
                        raise IOError('hello byte does not match')
                    else:
                        connecting = False
                        self._logger.debug('hello byte received')
                        self._parent.connected()
                else:
                    # read the length (4 bytes) of the incoming packet (this is blocking)
                    try:
                        pkt = self.pktReadAndDestuff(4)
                        if not pkt:
                            continue
                    
                        if len(pkt) != 4:
                            raise IOError('packet length does not match')
                    except (IOError, socket.error), e:
                        if not self._stopped:
                            raise
                        break
                    
                    pkt_len = int(struct.unpack('<I', pkt)[0])
    
                    try:
                        pkt = self.pktReadAndDestuff(pkt_len)
                        if not pkt:
                            continue
                    
                        if len(pkt) != pkt_len:
                            raise IOError('packet length does not match')
                    except (IOError,socket.error), e:
                        if not self._stopped:
                            raise
                        break
                    
                    self._parent.pktReceived(pkt)
        except Exception, e:
            self.disconnect()
            self._logger.exception(e.__str__())

        self._logger.info('died')
        
        
    def pktReadAndDestuff(self, length):
        out = self._stuffread
        if length == 1 and out:
            self._stuffread = ''
            return out
        index = 0
        while True:
            c = self.clientsocket.recv(1)
            if not c:
                raise IOError('None returned from socket')
            
            if ord(c) == STUFFING_BYTE and not self._stuff:
                self._stuff = True
            elif self._stuff:
                if ord(c) == STUFFING_BYTE:
                    out += c
                    self._stuff = False
                else:
                    self._logger.warn('stuffing mark reached')
                    self._stuff = False
                    self._stuffread = c
                    return None
            else:
                out += c
                    
                index += 1
            
            if len(out) == length:
                break

        self._stuffread = ''
        return out


    def stop(self):
        self._stopped = True
        self._gsnwriter.stop()
        if self._connected:
            try:
                self.clientsocket.close()
            except Exception, e:
                self._parent._parent.incrementExceptionCounter()
                self._logger.exception(e.__str__())
            self._connected = False
        self._logger.info('stopped')


    def disconnect(self):
        # synchonized method, guarantee that stop is called only once
        self._lock.acquire()
        if self._connected:
            self.stop()
            self._parent.disconnect()
        self._lock.release()


    def error(self, msg):
        self._parent._parent.incrementErrorCounter()
        self._logger.error(msg)



class PingTimer(Thread):
    
    '''
    data/instance attributes:
    _logger
    _interval
    _action
    _wait
    _timer
    _stopped
    '''
    
    def __init__(self, interval, action):
        Thread.__init__(self)
        self._logger = logging.getLogger(self.__class__.__name__)
        self._interval = interval
        self._action = action
        self._wait = None
        self._timer = Event()
        self._stopped = False
        
           
    def run(self):
        self._logger.info('started')
        # wait for first resume
        self._timer.wait()
        self._timer.clear()
        while not self._stopped:
            self._timer.wait(self._wait)
            if self._timer.isSet():
                self._timer.clear()
                continue
            self._action()
            self._logger.debug('action')
            
        self._logger.info('died')
    
    
    def pause(self):
        self._wait = None
        self._timer.set()
        self._logger.info('paused')
    
            
    def resume(self):
        self._wait = self._interval
        self._timer.set()
        self._logger.info('resumed')
    
    
    def stop(self):
        self._stopped = True
        self._timer.set()
        self._logger.info('stopped')



class PingWatchDog(PingTimer):
    
    def reset(self):
        self._timer.set()
        self._logger.debug('reset')



class GSNWriter(Thread):

    '''
    data/instance attributes:
    _logger
    _parent
    _sendqueue
    _work
    _stopped
    '''

    def __init__(self, parent):
        Thread.__init__(self)
        self._logger = logging.getLogger(self.__class__.__name__)
        self._parent = parent
        self._sendqueue = Queue.PriorityQueue(SEND_QUEUE_SIZE)
        self._work = Event()
        self._stopped = False


    def run(self):
        self._logger.info('started')
        while not self._stopped:
            self._work.wait()
            if self._stopped:
                break
            self._work.clear()
            # is there something to do?
            while self._parent._connected and not self._sendqueue.empty() and not self._stopped:
                try:
                    msg = self._sendqueue.get_nowait()[1]
                except Queue.Empty:
                    self._logger.warning('send queue is empty')
                    break
            
                if msg.__class__.__name__ != BackLogMessage.__name__ + 'Class':
                    pkt = msg
                else:
                    message = msg.getMessage()
                    msglen = len(message)
                    pkt = self.pktStuffing(struct.pack('<I', msglen) + message)
            
                try:
                    self._parent.clientsocket.sendall(pkt)
                    if msg.__class__.__name__ != BackLogMessage.__name__ + 'Class':
                        self._logger.debug('hello message sent')
                    else:
                        self._logger.debug('snd (%d,%d,%d)' % (msg.getType(), msg.getTimestamp(), msglen)) 
                except socket.error, e:
                    if not self._stopped:
                        self._parent.disconnect() # sets connected to false
                        self._logger.exception(e.__str__())                  
                finally:
                    self._sendqueue.task_done()
 
        self._logger.info('died')
        
        
    def pktStuffing(self, pkt):
        c = chr(STUFFING_BYTE)
        return pkt.replace(c, c+c)
        
        
    def sendHelloMsg(self):
        helloMsg = chr(STUFFING_BYTE) + chr(HELLO_BYTE)
        helloMsg += self.pktStuffing(struct.pack('<I', self._parent._parent._deviceid))
        self.addMsg(helloMsg, 0)


    def stop(self):
        self._stopped = True
        self._work.set()
        self.emptyQueue() # to unblock addResendMsg
        self._logger.info('stopped')


    def emptyQueue(self):
        while not self._sendqueue.empty():
            try:
                self._sendqueue.get_nowait()
                self._sendqueue.task_done()
            except Queue.Empty:
                self._logger.warning('send queue is empty (emptyQueue)')
                break


    def addMsg(self, msg, priority):
        if self._parent._connected and not self._stopped:
            try:
                self._sendqueue.put_nowait((priority, msg))
            except Queue.Full:
                self._logger.warning('send queue is full')
            self._work.set()
            return True
        return False

        
    def addResendMsg(self, msg, priority=100):
        # wait until send queue is empty
        self._sendqueue.join()
        assert self._sendqueue.not_empty != True
        if self._parent._connected and not self._stopped:
            try:
                self._sendqueue.put_nowait((priority, msg))
            except Queue.Full:
                self._logger.warning('send queue is full (resend)')
            self._work.set()
            return True
        return False