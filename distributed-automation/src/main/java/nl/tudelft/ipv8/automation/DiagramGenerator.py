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

pdf2 = pd.read_csv("C:/Users/jverb/Downloads/evaluations/parsed - evaluation-simulated-2021-01-02_20.43.03.csv")

columns = pdf2.columns
count = 0
diagram = 0
diagram_names = [
    "Our node is twice as slow as the other nodes",
    "Our node is twice as fast as the other nodes",
    "Our node joins late",
    "Data is non-i.i.d. (each node has 50% of the data)",
    "MNIST - 5 nodes",
    "CIFAR-10 - 5 nodes",
    "HAR - 5 nodes",
    "MNIST - 4 nodes",
    "MNIST - 5 nodes",
    "MNIST - 6 nodes",
    "MNIST - 5 nodes + 2 noise attackers",
    "MNIST - 5 nodes + 2 label-flip attackers",
    "MNIST - 5 nodes + 2 Fang (2020) KRUM attackers",
    "MNIST - 5 nodes + 2 Fang (2020) Trimmed Mean attackers",
    "MNIST - 5 nodes + 1 noise attacker",
    "MNIST - 5 nodes + 2 noise attackers",
    "MNIST - 5 nodes + 3 noise attackers",
]
for column in [column for column in pdf2.columns.tolist() if not column[0:7] == "Unnamed" and column != ' ' and column[1] != '.']:
    accuracies = [float(i) for i in pdf2[column].tolist() if i != ' ']
    iterations = [i * 10 for i in list(range(len(accuracies)))]
    if diagram == 2:
        iterations = [i + 100 for i in iterations]
    plt.plot(iterations, accuracies, label = column, linewidth = 4 if "bristle" in column else 2)
    count += 1
    if count % 6 == 0:
        plt.xlim(0, iterations[-1])
        plt.ylim(0, 1)
        plt.legend()
        plt.title(diagram_names[diagram])
        # plt.show()
        plt.savefig("C:/Users/jverb/Downloads/evaluations/" + column.split(" - ")[0] + ".png")
        plt.clf()
        diagram += 1
