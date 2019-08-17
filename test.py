from MonkeyApi import MonkeyApi
import time

mk=MonkeyApi('192.168.0.104',8888) #初始化，Ip为monkey手机端ip，可用adb shell netcfg查看，端口号为指定的端口号，默认为8888

try:
    mk.connect() #连接Monkey

    start = time.time()
    xml = mk.getXml()
    end = time.time()
    print(xml)
    print("getdocument cost: ", (end - start)*1000)

    time.sleep(1)
    start = time.time()
    mk.click(500,550) #点击（60，150）
    end = time.time()
    print("click cost: ", (end - start)*1000)

    mk.back()# 返回操作

    time.sleep(1)
    start = time.time()
    bitmapbase64 = mk.getScreenShotBase64()  #获取截屏的base64 String
    end = time.time()
    print("screenshot cost: ", (end - start)*1000)
    print(bitmapbase64)


    start = time.time()
    xml = mk.getXml()
    end = time.time()
    print(xml)
    print("getdocument cost: ", (end - start)*1000)

    mk.disconnect()# 断开连接
except Exception as msg:
    print(msg)
    mk.disconnect()