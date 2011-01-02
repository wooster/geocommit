package com.geocommit.source;

import com.geocommit.Geocommit
import scala.tools.nsc.io._
import scala.util.matching.Regex
import scala.collection.mutable.StringBuilder

class Bitbucket extends GeocommitSource {
    def clone(repo: String) {
        val repoDir = getRepoDir(repo)
        // foreach necessary to wait for termination
        println("hg clone \"" + repo + "\" " + repoDir)
        cmdWait(List("hg", "clone", repo, repoDir))
    }

    def delete(repo: String) {
        val repoDir = getRepoDir(repo)
        println("rm " + repoDir)
        Process("rm " + repoDir)
    }

    def getGeocommits(repo: String, id: String): List[Geocommit] = {
        val repoDir = getRepoDir(repo)

        // truly random, banged head on keyboard
        val sep1 = "ghhugrij5oijtu3099324knmvvfu0g9u34fd09ufs"
        val sep2 = "dfggddgdfgijorejgehui43n0ßt4t9t4ugt4ggre"

        val output = new StringBuilder;

        println("hg log --template \"{node}" + sep1 + "{author}" + sep1 + "{desc}" + sep2 + "\"")
        Process(
                "hg log --template \"{node}" + sep1 + "{author}" + sep1 + "{desc}" + sep2 + "\"",
                cwd = repoDir
            ).addString(output)

        output.toString.split(sep2).map(
                _ split sep1
            ).map(
                (data: Array[String]) => {
                    val r = new Regex("(geocommit\\(1\\.0\\):[^;]+;)")
                    println(data)
                    val matches = r.findAllIn(data(2)).toList
                    if (matches.isEmpty) {
                        null
                    }
                    else {
                        (data(0), data(1), data(2), matches.last)
                    }
                }
            ).map{
                case (rev: String, author: String, message: String, geoData: String) => {
                    Geocommit(id, rev, message, author, geoData)
                }
                case _ => null
            }.toList.filter{
                case null => false
                case _ => true
            }
    }
}