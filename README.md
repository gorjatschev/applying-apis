# About
This repository contains the code underlying the master thesis "Applying API Categories to the Abstractions Using APIs" (2021) written by Katharina Gorjatschev in the Software Languages Team at the Computer Science Department of the University of Koblenz and Landau.

This readme was created to help the MSR 2021/22 course to understand the code structure easier. 

# Code structure
The code consists of Java and Python code. Java is used for data collection and parsing, Python is used for analysis and visualization (because of PySpark and Plotly). All Java code is executed from the `Application.java` file. There are four Python files. Three of them are independent of each other and all have an own main function for the execution of the code. The last file `utils.py` is just a utils file.

### 1. Collection of repositories
Collects repositories from GitHub.
* Where: `Application.java` (line 51)
* Involved files: `RepositoriesPicker.java`, `Utils.java`

### 2. Collection and analysis of the dependencies of the collected repositories
This step is performed to decide which repositories and dependencies are worth looking into (collection performed in Java, analysis performed in Python).
* Where: `Application.java` (line 56 and 61), `dependencies_counter.py` (line 125-130)
* Involved files: `RepositoriesPicker.java`, `RepositoryManager.java`, `DependenciesManager.java`, `Utils.java`, `dependencies_counter.py`, `utils.py`

### 3. Selection of repositories
Selects the repositories from the collected repositories that actually will be parsed and analysed.
* Where: `Application.java` (line 66)
* Involved files: `RepositoriesPicker.java`, `Utils.java`

### 4. Parsing of repositories
Downloads Java files, collects dependencies from POM files, collects their MCR categories and MCR tags, downloads the dependencies, parses the Java files, resolves class usages in the Java files, and allocates dependencies to the found API usages.
* Where: `Application.java` (line 70f)
* Involved files: `RepositoryManager.java`, `DependenciesManager.java`, `DependenciesDownloader.java`, `Parser.java`, `ClassOrInterfaceSymbolResolver.java`, `DependenciesAllocator.java`, `Utils.java`

### 5. Analysis
Analyses repositories by selecting all API usages and summarizing them in package, class, and method abstractions. Afterwards, analyses those repositories based on two dependencies (dependency pair) by counting the API usages of the dependencies and sampling abstractions for manual analysis/classification.
* Where: `repositories_analyzer.py` (line 222-224)
* Involved files: `repositories_analyzer.py`, `utils.py`

### 6. Visualization
Characterizes abstractions of repositories and visualizes the repositories.
* Where: `repositories_visualizer.py` (line 156-165)
* Involved files: `repositories_visualizer.py`, `utils.py`

# Software
* Java 11 (Maven project)
* Python 3.9.6 (plotly==5.1.0, pyspark==3.1.2)
