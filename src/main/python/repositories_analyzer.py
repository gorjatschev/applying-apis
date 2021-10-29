from pathlib import Path

import pyspark.sql.functions as F
from pyspark import SparkContext
from pyspark.sql.session import SparkSession
from pyspark.sql.types import IntegerType, StringType, StructField, StructType

import utils as u


def analyze_repository(repository: str, abstraction: str):
    """
    Analyzes the `repository` on the abstraction level `abstraction` ("package", "class", "method").
    Creates a `proportion_file` and a `sets_file`.
    """
    proportion_file = u.analyzed_data_dir + abstraction + "/" + \
        u.api_proportion_file + abstraction + "_" + repository + ".csv"
    sets_file = u.analyzed_data_dir + abstraction + "/" + \
        u.api_sets_file + abstraction + "_" + repository + ".csv"
    if Path(proportion_file).exists() & Path(sets_file).exists():
        print("Analysis for " + repository + " already exists.")
        return None

    data_file = u.data_dir + repository + ".csv"
    if not Path(data_file).exists():
        print("Skipping " + repository + ", " + data_file + " missing.")
        return None

    df1 = u.read_csv(spark, data_file)
    if (df1.rdd.isEmpty()):
        print("Skipping " + repository + ", " + data_file + " is empty.")
        return None

    df1 = df1.filter(F.col(u.isAPIClass) == "true").na.fill("")

    grouping_columns = [u.packageName]
    if abstraction == "method":
        grouping_columns.extend([u.className, u.methodName])
    elif abstraction == "class":
        grouping_columns.append(u.className)
    grouping_columns_api = grouping_columns.copy()
    grouping_columns_api.extend([u.api,  u.mcrCategories, u.mcrTags])

    counted_all = df1.groupBy(grouping_columns) \
        .agg(F.count(u.api).alias(u.countAll))
    counted = df1.groupBy(grouping_columns_api) \
        .agg(F.count(u.api).alias(u.count))
    joined = counted.join(counted_all, grouping_columns, "outer") \
        .withColumn(u.proportion, F.round(F.col(u.count) / F.col(u.countAll), 2))

    u.delete_dir(u.spark_dir)
    u.write_csv(joined.coalesce(1), u.spark_dir)
    u.copy_csv(u.spark_dir, proportion_file)

    df2 = u.read_csv(spark, proportion_file)
    df2 = df2.groupBy(grouping_columns).agg(F.regexp_replace(
        F.sort_array(F.collect_list(u.api)).cast("string"), " ", "").alias(u.apis))

    u.delete_dir(u.spark_dir)
    u.write_csv(df2.coalesce(1), u.spark_dir)
    u.copy_csv(u.spark_dir, sets_file)


def analyze(repositories_selected_file: str):
    """
    Analyzes all repositories in the `repositories_selected_file` on all abstraction levels 
    ("package", "class", "method").
    """
    df = u.read_csv(spark, repositories_selected_file)
    for repository in df.select("repositoryName").rdd.flatMap(lambda x: x).collect():
        analyze_repository(repository.replace("/", "_"), "method")
        analyze_repository(repository.replace("/", "_"), "class")
        analyze_repository(repository.replace("/", "_"), "package")


def count_pairs_in_abstraction_and_repo(repository: str, abstraction: str, dependency1: str, dependency2: str):
    """
    Counts the occurrences of the dependencies `dependency1` and `dependency2` in the `repository` 
    on the abstraction level `abstraction`. Returns a list that contains `repository`, 
    `count_dependency1`, `count_dependency2`, and `count_both`.
    """
    sets_file = u.analyzed_data_dir + abstraction + "/" + \
        u.api_sets_file + abstraction + "_" + \
        repository.replace("/", "_") + ".csv"
    if not Path(sets_file).exists():
        print("Skipping " + repository + ", " + sets_file + " missing.")
        return []
    df = u.read_csv(spark, sets_file)
    df = df.withColumn(u.apis, F.split(
        F.regexp_replace(u.apis, "[\[\]]", ""), ","))

    count_both = df.filter(F.array_contains(u.apis, dependency1) &
                           F.array_contains(u.apis, dependency2)).count()
    count_dependency1 = df.filter(
        F.array_contains(u.apis, dependency1)).count()
    count_dependency2 = df.filter(
        F.array_contains(u.apis, dependency2)).count()

    return [repository, count_dependency1, count_dependency2, count_both]


def count_pairs_in_abstraction(abstraction: str, repositories_selected_file: str, dependency1: str, dependency2: str):
    """
    Counts the occurrences of the dependencies `dependency1` and `dependency2` in all repositories 
    contained in the `repositories_selected_file` on the abstraction level `abstraction`. Writes 
    them in a CSV file. Returns a list that contains `abstraction`, 
    `count_dependency1`, `count_dependency2`, and `count_both`. 
    """
    counts = []
    df = u.read_csv(spark, repositories_selected_file)

    for repository in df.select("repositoryName").rdd.flatMap(lambda x: x).collect():
        count = count_pairs_in_abstraction_and_repo(
            repository, abstraction, dependency1, dependency2)
        if count != []:
            counts.append(count)

    schema = StructType([StructField("repositoryName", StringType(), False),
                        StructField(dependency1, IntegerType(), False),
                        StructField(dependency2, IntegerType(), False),
                        StructField("both", IntegerType(), False)])
    data = spark.createDataFrame(counts, schema)

    u.delete_dir(u.spark_dir)
    u.write_csv(data.coalesce(1), u.spark_dir)
    u.copy_csv(u.spark_dir, u.analyzed_data_dir + "counts" + "/" + dependency1.replace(":", "_")
               + "_" + dependency2.replace(":", "_") + "_" + abstraction + ".csv")

    count_dependency1 = data.select(
        F.sum(F.col("`" + dependency1 + "`"))).collect()[0][0]
    count_dependency2 = data.select(
        F.sum(F.col("`" + dependency2 + "`"))).collect()[0][0]
    count_both = data.select(F.sum(F.col("both"))).collect()[0][0]
    return [abstraction, count_dependency1, count_dependency2, count_both]


def count_pairs(repositories_selected_file: str, dependency1: str, dependency2: str):
    """
    Counts the occurrences of the dependencies `dependency1` and `dependency2` in all repositories 
    contained in the `repositories_selected_file` on all abstraction levels ("package", "class", "method").
    """
    all_counts = []
    all_counts.append(count_pairs_in_abstraction(
        "method", repositories_selected_file, dependency1, dependency2))
    all_counts.append(count_pairs_in_abstraction(
        "class", repositories_selected_file, dependency1, dependency2))
    all_counts.append(count_pairs_in_abstraction(
        "package", repositories_selected_file, dependency1, dependency2))

    schema = StructType([StructField("abstraction", StringType(), False),
                         StructField(dependency1, IntegerType(), False),
                         StructField(dependency2, IntegerType(), False),
                         StructField("both", IntegerType(), False)])
    data = spark.createDataFrame(all_counts, schema)

    u.delete_dir(u.spark_dir)
    u.write_csv(data.coalesce(1), u.spark_dir)
    u.copy_csv(u.spark_dir, u.analyzed_data_dir + "counts" + "/" + "counted_" + dependency1.replace(":", "_")
               + "_" + dependency2.replace(":", "_") + ".csv")


def sample_abstractions(repositories: int, samples_in_repository: int, usage_limit: int, abstraction: str, dependency1: str, dependency2: str):
    """
    Samples (number:)`repositories` repositories that have (number:)`usage_limit` abstractions of type `abstraction` 
    that contain the dependencies `dependency1` and `dependency2`. From each repository (number:)`samples_in_repository` 
    abstractions are sampled.
    """
    sample = []
    df = u.read_csv(spark, u.analyzed_data_dir + "counts" + "/" + dependency1.replace(":", "_")
                    + "_" + dependency2.replace(":", "_") + "_" + abstraction + ".csv")
    count = df.count()
    df = df.filter(F.col("both") >= usage_limit).select("repositoryName")
    count_filtered = df.count()
    print("Filtered out", count_filtered, "out of", count,
          "repositories with at least", usage_limit, "abstractions that use both dependencies.")
    print("Sampled", repositories, "repositories out of", count_filtered)

    for repository in sc.parallelize(df.rdd.takeSample(False, repositories)).map(tuple).collect():
        data = u.read_csv(spark, u.analyzed_data_dir + abstraction + "/" + u.api_sets_file +
                          abstraction + "_" + repository[0].replace("/", "_") + ".csv")
        data = data.withColumn(u.apis, F.split(
            F.regexp_replace(u.apis, "[\[\]]", ""), ",")) \
            .filter(F.array_contains(u.apis, dependency1) &
                    F.array_contains(u.apis, dependency2)) \
            .withColumn("repositoryName", F.lit(repository[0])).na.fill("")

        sample_list = sc.parallelize(
            data.rdd.takeSample(False, samples_in_repository)).map(tuple).collect()
        for t in sample_list:
            sample.append(t)

    schema = StructType([StructField(u.filePath, StringType(), False),
                         StructField(u.packageName, StringType(), False)])
    if abstraction == "method":
        schema.add(StructField(u.className, StringType(), False))
        schema.add(StructField(u.methodName, StringType(), False))
    elif abstraction == "class":
        schema.add(StructField(u.className, StringType(), False))
    schema.add(StructField(u.apis, StringType(), False))
    schema.add(StructField("repositoryName", StringType(), False))

    data = spark.createDataFrame(sample, schema) \
        .withColumn(u.apis, F.regexp_replace(u.apis, " ", ""))

    u.delete_dir(u.spark_dir)
    u.write_csv(data.coalesce(1), u.spark_dir)
    u.copy_csv(u.spark_dir, u.analyzed_data_dir + "sampled_abstractions" + "/"
               + "sampled_abstractions_" + dependency1.replace(":", "_") + "_"
               + dependency2.replace(":", "_") + ".csv")


if __name__ == "__main__":
    sc = SparkContext("local", "applying-apis")
    spark = SparkSession(sc)

    dependency1 = "org.apache.lucene:lucene-analyzers-common"
    dependency2 = "org.apache.lucene:lucene-core"
    repositories_selected_file = u.repositories_selected_dir + \
        dependency1.replace(":", "_") + "_" + \
        dependency2.replace(":", "_") + ".csv"

    analyze(repositories_selected_file)
    count_pairs(repositories_selected_file, dependency1, dependency2)
    sample_abstractions(5, 2, 5, "method", dependency1, dependency2)
