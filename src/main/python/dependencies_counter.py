import pyspark.sql.functions as F
from pyspark import SparkContext
from pyspark.ml import Pipeline
from pyspark.ml.feature import HashingTF, MinHashLSH
from pyspark.sql.session import SparkSession
from pyspark.sql.types import StringType

import utils as u


def countEach(column: str, new_column: str):
    """
    Counts each object in the column `column` ("dependencies" or "mcrTags") of the file in `df` 
    and saves the results in a new file with the column `new_column`.
    """
    u.delete_dir(u.spark_dir)

    df = u.read_csv(spark, u.output_dir + u.repos_with + column + ".csv")
    df = df.withColumn(column, F.split(
        F.regexp_replace(column, "[\[\]]", ""), ","))

    list = []
    for row in df.rdd.collect():
        for i in row[column]:
            list.append(i)

    data = spark.createDataFrame(list, StringType()).toDF(column) \
        .groupBy(column).count().withColumnRenamed(column, new_column)

    u.write_csv(data.coalesce(1), u.spark_dir)
    u.copy_csv(u.spark_dir, u.output_dir + column + "_counted.csv")


def countSets(column: str):
    """
    Counts each set in the column `column` ("dependencies" or "mcrTags") of the file in `df` 
    and saves the results in a new file.
    """
    u.delete_dir(u.spark_dir)

    df = u.read_csv(spark, u.output_dir + u.repos_with + column + ".csv")
    df = df.groupBy(column).count()

    u.write_csv(df.coalesce(1), u.spark_dir)
    u.copy_csv(u.spark_dir, u.output_dir + column + "_sets_counted.csv")


def computeJaccardSimilarity(column: str, threshold: float):
    """
    Computes the Jaccard similarity with `threshold` on the column `column` ("dependencies" or "mcrTags") 
    of the file in `df` and saves the results in a new file.
    """
    u.delete_dir(u.spark_dir)

    df = u.read_csv(spark, u.output_dir + u.repos_with + column + ".csv")
    df = df.withColumn(column, F.split(
        F.regexp_replace(column, "[\[\]]", ""), ","))

    model = Pipeline(stages=[
        HashingTF(inputCol=column, outputCol="vectors"),
        MinHashLSH(inputCol="vectors", outputCol="lsh", numHashTables=10)
    ]).fit(df)

    data_t = model.transform(df)
    data_s = model.stages[-1].approxSimilarityJoin(
        data_t, data_t, 1 - threshold, distCol="similarity")

    result = data_s.withColumn("intersection", F.array_intersect(
        F.col("datasetA." + column), F.col("datasetB." + column)).cast("string")) \
        .select(F.col("datasetA.repositoryName").alias("repositoryName1"),
                F.col("datasetB.repositoryName").alias("repositoryName2"),
                F.col("intersection"), F.col("similarity")) \
        .filter("repositoryName1 < repositoryName2") \
        .withColumn("similarity", F.round(1 - F.col("similarity"), 2))

    u.write_csv(result.coalesce(1), u.spark_dir)
    u.copy_csv(u.spark_dir, u.output_dir +
               u.repos_with + column + "_similarity.csv")


def countPairs():
    """
    Creates all dependency pairs with dependencies (count >= 100) in the file in `df2`, 
    counts the occurrences of each dependency pair in the file in `df1` and saves the 
    results in a new file.
    """
    u.delete_dir(u.spark_dir)

    df1 = u.read_csv(spark, u.output_dir + u.repos_with +
                     u.dependencies + ".csv")
    df1 = df1.withColumn(u.dependencies, F.split(
        F.regexp_replace(u.dependencies, "[\[\]]", ""), ","))

    df2 = u.read_csv(spark, u.output_dir + u.dependencies + "_counted.csv")
    df2 = df2.filter(F.col("count") >= 100)

    pairs = df2.select(F.col("dependency").alias("dependency1")) \
        .crossJoin(df2.select(F.col("dependency").alias("dependency2"))) \
        .filter("dependency1 < dependency2")

    counted = pairs.join(df1, F.array_contains(df1[u.dependencies], pairs["dependency1"]) &
                         F.array_contains(df1[u.dependencies], pairs["dependency2"])) \
        .groupBy("dependency1", "dependency2").count().drop("repositoryName").drop(u.dependencies)

    df3 = df2.withColumnRenamed("dependency", "dependency1") \
        .withColumnRenamed("count", "count1")
    df4 = df2.withColumnRenamed("dependency", "dependency2") \
        .withColumnRenamed("count", "count2")

    data = counted.join(df3, "dependency1").join(df4, "dependency2") \
        .select("dependency1", "dependency2", "count", "count1", "count2")
    data = data.withColumn("proportion1", F.round(data["count"] / data["count1"], 2)) \
        .withColumn("proportion2",  F.round(data["count"] / data["count2"], 2)) \
        .withColumn("maxProportion", F.greatest(F.col("proportion1"), F.col("proportion2")))

    u.write_csv(data.coalesce(1), u.spark_dir)
    u.copy_csv(u.spark_dir, u.output_dir +
               u.dependencies + "_pairs_counted.csv")


if __name__ == "__main__":
    sc = SparkContext("local", "applying-apis")
    spark = SparkSession(sc)

    countEach("dependencies", "dependency")
    countSets("dependencies")
    countSets("mcrTags")
    computeJaccardSimilarity("dependencies", 0.7)
    computeJaccardSimilarity("mcrTags", 0.7)
    countPairs()
