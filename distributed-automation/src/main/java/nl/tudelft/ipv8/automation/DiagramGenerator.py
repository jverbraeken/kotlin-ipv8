# libraries
import matplotlib
gui_env = ['TKAgg','GTKAgg','Qt4Agg','WXAgg']
for gui in gui_env:
    try:
        print("testing", gui)
        matplotlib.use(gui, force=True)
        from matplotlib import pyplot as plt
        break
    except:
        continue
print("Using:",matplotlib.get_backend())
import numpy as np
import pandas as pd
from collections import defaultdict

pdf2 = pd.read_csv("C:/Users/jverb/Downloads/evaluations/parsed evaluations.csv")

columns = pdf2.columns
diagram_names = [
    "Our node is 5x as slow as the other nodes",
    "Our node is 5x as fast as the other nodes",
    "Our node joins late",

    "MNIST - 10 nodes",
    "CIFAR-10 - 10 nodes",
    "WISDM - 10 nodes",

    "MNIST - 10 nodes + 4 all-label-flip attackers",
    "CIFAR-10 - 10 nodes + 4 all-label-flip attackers",
    "WISDM - 10 nodes + 4 all-label-flip attackers",

    "MNIST - 10 nodes + 4 2-label-flip attackers",
    "MNIST - 10 nodes + 4 noise attackers",
    "MNIST - 10 nodes + 4 Fang (2020) KRUM attackers",
    "MNIST - 10 nodes + 4 Fang (2020) Trimmed Mean attackers",

    "MNIST - 10 nodes non-i.i.d. + 4 noise attackers",
    "MNIST - 10 nodes non-i.i.d. + 4 2-label-flip attackers",
    "MNIST - 10 nodes non-i.i.d. + 4 all-label-flip attackers",
    "MNIST - 10 nodes non-i.i.d. + 4 Fang (2020) KRUM attackers",
    "MNIST - 10 nodes non-i.i.d. + 4 Fang (2020) Trimmed Mean attackers",

    "MNIST - 10 nodes non-i.i.d. + 2 all-label-flip attackers",
    "MNIST - 10 nodes non-i.i.d. + 8 all-label-flip attackers",
]
label_mapping = {
    "average": "FedAvg",
    "bridge": "Bridge",
    "bristle": "Pro-Bristle",
    "krum": "KRUM",
    "median": "Coordinate-wise Median",
    "mozi": "MOZI"
}
plt_regular_data = []
plt_regular_data_all = []
plt_transfer_data = []
plt_transfer_data_all = []
plt_bound_data = defaultdict(lambda: {})
skip = False
colors = {
    "average": "darkviolet",
    "bridge": "orange",
    "bristle": "green",
    "krum": "red",
    "median": "royalblue",
    "mozi": "brown"
}
diagram = 0
for column in [column for column in pdf2.columns.tolist() if not column[0:7] == "Unnamed"]:
    if skip:
        skip = False
        continue
    if column == ' ' or column[1] == '.':
        skip = True

        if len(plt_regular_data) > 0:
            plt_regular_data_all.append(plt_regular_data.copy())
            plt_regular_data.clear()

        if len(plt_transfer_data) > 0:
            plt_transfer_data_all.append(plt_transfer_data.copy())
            plt_transfer_data.clear()
        diagram += 1
    else:
        accuracies = [float(i) for i in pdf2[column].tolist() if i != ' ']
        iterations = [i * 10 for i in list(range(len(accuracies)))]
        if "Bound" in column:
            split = column.split(" - ")
            plt_bound_data[split[0]][split[2]] = (iterations, accuracies, column)
        else:
            if diagram == 2:
                iterations = [i + 150 for i in iterations]
            if "regular" in column:
                plt_regular_data.append((iterations, accuracies, column, 4 if "bristle" in column else 2, column.split(" - ")[1]))
            if "transfer" in column:
                plt_transfer_data.append((iterations, accuracies, column, 4 if "bristle" in column else 2, column.split(" - ")[1]))

for (diagram, data) in enumerate(plt_regular_data_all):
    fig = plt.figure(figsize=(3, 2.5))
    fig.patch.set_facecolor('none')
    for (a, b, c, d, e) in data:
        plt.plot(a, b, label=label_mapping[c.split(" - ")[1]], linewidth=d, color=colors[e])
    if diagram > 2:
        print(data[-1][2].split(" - ")[0] + " => " + diagram_names[diagram])
        bound = plt_bound_data[" Figure MinBound " + diagram_names[diagram].split(" - ")[0] + " _ " + ("noniid" if "non-i.i.d." in diagram_names[diagram] else "full")]
        plt.plot(bound["regular"][0], bound["regular"][1], ":", label="Local run limited dataset", linewidth=2, color="black")
        bound = plt_bound_data[" Figure MaxBound " + diagram_names[diagram].split(" - ")[0] + " _ " + ("noniid" if "non-i.i.d." in diagram_names[diagram] else "full")]
        plt.plot(bound["regular"][0], bound["regular"][1], "--", label="Local run full dataset", linewidth=1, color="black")
    plt.xlim(0, 300)
    plt.ylim(0, 1)
    ax = plt.gca()
    ax.set_facecolor('#fefcf2')
    # plt.rc("xtick", labelsize=20)
    # plt.rc("ytick", labelsize=20)
    # plt.xlabel("#iterations", fontsize=20)
    # plt.ylabel("accuracy", fontsize=20)
    # plt.legend(loc="lower right")
    # plt.title(diagram_names[diagram])
    plt.savefig("C:/Users/jverb/Downloads/evaluations/" + data[-1][2].split(" - ")[0] + " - regular - " + diagram_names[diagram] + ".png", dpi=200, bbox_inches='tight', pad_inches=0)
    plt.clf()

for (diagram, data) in enumerate(plt_transfer_data_all):
    fig = plt.figure(figsize=(3, 2.5))
    fig.patch.set_facecolor('none')
    for (a, b, c, d, e) in data:
        plt.plot(a, b, label=label_mapping[c.split(" - ")[1]], linewidth=d, color=colors[e])
    if diagram > 2:
        print(data[-1][2].split(" - ")[0] + " => " + diagram_names[diagram])
        bound = plt_bound_data[" Figure MinBound " + diagram_names[diagram].split(" - ")[0] + " _ " + ("noniid" if "non-i.i.d." in diagram_names[diagram] else "full")]
        plt.plot(bound["transfer"][0], bound["transfer"][1], ":", label="Local run limited dataset", linewidth=2, color="black")
        bound = plt_bound_data[" Figure MaxBound " + diagram_names[diagram].split(" - ")[0] + " _ " + ("noniid" if "non-i.i.d." in diagram_names[diagram] else "full")]
        plt.plot(bound["transfer"][0], bound["transfer"][1], "--", label="Local run full dataset", linewidth=1, color="black")
    plt.xlim(0, 300)
    plt.ylim(0, 1)
    plt.yticks([])
    # plt.rcParams['axes.facecolor'] = 'black'
    # plt.legend(loc="lower right", mode="expand")
    # plt.title(diagram_names[diagram])
    ax = plt.gca()
    ax.set_facecolor('#fefcf2')
    plt.savefig("C:/Users/jverb/Downloads/evaluations/" + data[-1][2].split(" - ")[0] + " - transfer - " + diagram_names[diagram] + ".png", dpi=200, bbox_inches='tight', pad_inches=0)
    plt.clf()
