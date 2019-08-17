import socket
import sys
import struct

class MonkeyApi:

    def __init__(self,host,port):
        self.client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.host=host
        self.port=port


    def connect(self):
        self.client.settimeout(5.0)
        self.client.connect((self.host, self.port))


    def disconnect(self):
        try:
            actiontype = int(4).to_bytes(4, byteorder="big", signed=True)
            self.client.send(actiontype)
            message=self.receiveMessage()
            self.client.close()
        except socket.timeout as msg:
            print(msg)
            self.client.close()
            sys.exit(1)
        return message


    def click(self, x, y):
        x=int(x)
        y=int(y)
        try:
            actiontype = int(0).to_bytes(4, byteorder="big", signed=True)
            self.client.send(actiontype)
            length = int(2).to_bytes(4, byteorder="big", signed=True)
            self.client.send(length)
            x_byte = x.to_bytes(4, byteorder="big", signed=True)
            self.client.send(x_byte)
            y_byte = y.to_bytes(4, byteorder="big", signed=True)
            self.client.send(y_byte)
            message=self.receiveMessage()
        except socket.timeout as msg:
            print(msg)
            self.client.close()
            sys.exit(1)

        return message


    def getScreenShotBase64(self):
        actiontype = int(8).to_bytes(4, byteorder="big", signed=True)
        self.client.send(actiontype)
        message=self.receiveMessage()
        return message


    def getXml(self):
        try:
            actiontype = int(3).to_bytes(4, byteorder="big", signed=True)
            self.client.send(actiontype)
            message=self.receiveMessage()
        except socket.timeout as msg:
            print(msg)
            self.client.close()
            sys.exit(1)

        return message


    def back(self):
        try:
            actiontype = int(7).to_bytes(4, byteorder="big", signed=True)
            self.client.send(actiontype)
            message=self.receiveMessage()
        except socket.timeout as msg:
            print(msg)
            self.client.close()
            sys.exit(1)
        return message


    def receiveMessage(self):
        try:
            lendata = self.client.recv(4)
            if lendata:
                length = int.from_bytes(lendata, byteorder='big', signed=True)
            else:
                raise Exception('Client disconnected')
            #print(length)
            recvd_size = 0
            total_data=bytearray()
            while not recvd_size == length:
                if length - recvd_size > 1024:
                    data = self.client.recv(1024)
                    recvd_size += len(data)
                else:
                    data = self.client.recv(length - recvd_size)
                    recvd_size += len(data)
                total_data.extend(data)
        except socket.timeout as msg:
            print(msg)
            self.client.close()
            sys.exit(1)

        return "".join(total_data.decode('utf-8'))

