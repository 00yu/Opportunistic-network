#!/usr/bin/env python
# -*- coding: utf-8 -*-

'''
Plot a figure from a batch of MessageStatsReports
绘图
'''
import matplotlib.pyplot as plt
from pylab import *
import numpy as np
import os
import math
# get .txt document
import re
from matplotlib.ticker import MultipleLocator
from pylab import mpl

mpl.rcParams['font.sans-serif'] = ['SimHei']
mpl.rcParams['axes.unicode_minus'] = False


def main():
    # 放txt文件的位置(需要修改)
    dirpath = 'C:/Users/kk/Desktop/results/HLRA LCEpidemic Epidemic SprayAndWait/'
    rootdir = os.path.join(dirpath)
    # 需要绘制的类型(填写对应的数字)
    # Delivery Rate：9
    #  Overhead Ratio：11
    # Average Latency(ms)：12
    # Average hopcount：14
    messagetype = 14
    # list11存放第一个算法txt文件名
    # list12存放第一个算法文件中对应变量的值
    # list13存放第一个算法文件名中的缓存大小
    list11 = []
    list12 = []
    list13 = []
    list21 = []
    list22 = []
    list23 = []
    list31 = []
    list32 = []
    list33 = []
    # 算法名称(注意与生成的txt文件开头一致)
    name1 = "Epidemic"
    name2 = "HLRA"
    name3 = "LCEpidemic"
    # read
    for (dirpath, dirnames, filenames) in os.walk(rootdir):
        for filename in filenames:
            if os.path.splitext(filename)[1] == '.txt' and filename.startswith(name1):
                list11.append(filename)
            if os.path.splitext(filename)[1] == '.txt' and filename.startswith(name2):
                list21.append(filename)
            if os.path.splitext(filename)[1] == '.txt' and filename.startswith(name3):
                list31.append(filename)

    for i in range(0, len(list11), 1):
        fname = list11[i]
        file = open(dirpath + fname)
        for j in range(0, messagetype, 1):
            file.readline()
        list12.append(file.readline().split(':')[1])
        file.close()
    for i in range(0, len(list12), 1):
        list13.append(float(re.findall(r"\d+\.?\d*", list11[i])[0]))

    for i in range(0, len(list21), 1):
        file = open(dirpath + list21[i])
        for j in range(0, messagetype, 1):
            file.readline()
        list22.append(file.readline().split(':')[1])
        file.close()
    for i in range(0, len(list22), 1):
        list23.append(float(re.findall(r"\d+\.?\d*", list21[i])[0]))

    for i in range(0, len(list31), 1):
        file = open(dirpath + list31[i])
        for j in range(0, messagetype, 1):
            file.readline()
        list32.append(file.readline().split(':')[1])
        file.close()
    for i in range(0, len(list32), 1):
        list33.append(float(re.findall(r"\d+\.?\d*", list31[i])[0]))

    # Step 1: Read files
    # Get filenames
    # Read files
    x1 = np.array(list13, dtype=float)  # 第一个算法缓存大小
    y1 = np.array(list12, dtype=float)  # 第一个算法投递率
    x11 = []  # 排序后缓存大小
    y11 = []  # 排序后投递率
    data1 = []
    for i in range(0, len(x1), 1):
        data1.append((x1[i], y1[i]))
    data1.sort(key=lambda s: s[0])
    for i in range(0, len(x1), 1):
        x11.append(data1[i][0])
        y11.append(data1[i][1])

    x2 = np.array(list23, dtype=float)
    y2 = np.array(list22, dtype=float)
    x21 = []
    y21 = []
    data2 = []
    for i in range(0, len(x2), 1):
        data2.append((x2[i], y2[i]))
    data2.sort(key=lambda s: s[0])
    for i in range(0, len(x2), 1):
        x21.append(data2[i][0])
        y21.append(data2[i][1])

    x3 = np.array(list33, dtype=float)
    y3 = np.array(list32, dtype=float)
    x31 = []
    y31 = []
    data3 = []
    for i in range(0, len(x3), 1):
        data3.append((x3[i], y3[i]))
    data3.sort(key=lambda s: s[0])
    for i in range(0, len(x3), 1):
        x31.append(data3[i][0])
        y31.append(data3[i][1])

    font = {'family': 'Times New Roman',
            'weight': 'normal',
            'size': 15,
            }
    plt.plot(x11, y11, '.k-', label=name1, linewidth=2,
             markeredgewidth=1, markerfacecolor='k', markersize=8)
    plt.plot(x21, y21, '^c-', label=name2, linewidth=2,
             markeredgewidth=1, markerfacecolor='c', markersize=8)
    plt.plot(x31, y31, '*r-', label=name3, linewidth=2,
             markeredgewidth=1, markerfacecolor='r', markersize=8)
    plt.xlabel('Buffer size(MB)', font)
    # 更改对应的类型
    plt.ylabel('Average hopcount', font)
    plt.legend()
    plt.show()
    return


if __name__ == '__main__':
    main()
