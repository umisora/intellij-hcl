/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.intellij.plugins.hcl.terraform.config.model.download

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.beust.klaxon.string
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.io.ZipUtil
import com.intellij.util.text.VersionComparatorUtil
import org.apache.http.HttpHeaders
import org.apache.http.HttpResponse
import org.apache.http.HttpStatus
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpRequestBase
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.conn.ssl.SSLContexts
import org.apache.http.conn.ssl.TrustSelfSignedStrategy
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler
import org.apache.http.impl.client.DefaultRedirectStrategy
import org.apache.http.impl.client.HttpClientBuilder
import org.intellij.plugins.hcl.terraform.HttpUtil
import org.intellij.plugins.hcl.terraform.TerraformToolProjectSettings
import java.io.*
import java.text.MessageFormat

class ModelDownloader {
  companion object {
    internal val LOG = Logger.getInstance(ModelDownloader::class.java)
    private val CONTENT_SRC = "https://github.com/VladRassokhin/terraform-metadata/archive/{0}.zip"
    private val TAGS_SRC = "https://api.github.com/repos/VladRassokhin/terraform-metadata/tags"
    fun getSrcUrl(revision: String): String {
      return MessageFormat.format(CONTENT_SRC, revision)
    }
  }

  // Next calls should be used
  // 1. GET /repos/:owner/:repo/tags
  //  ^ ETag is persistent
  // If needed - proceed below
  // Determine latest tag using semantic version comparation
  // Download latest metadata version
  // 2. GET /:owner/r:repo/archive/:tag.zip
  // Unpack to directory with name == tag
  // Change internal/UI property which backs metadata version selection

  // /tags output
  /*
  [
    {
      "name": "v0.5.12.2",
      "zipball_url": "https://api.github.com/repos/VladRassokhin/intellij-hcl/zipball/v0.5.12.2",
      "tarball_url": "https://api.github.com/repos/VladRassokhin/intellij-hcl/tarball/v0.5.12.2",
      "commit": {
        "sha": "29190dd577a79fa5b74f5271aa10990d61bec798",
        "url": "https://api.github.com/repos/VladRassokhin/intellij-hcl/commits/29190dd577a79fa5b74f5271aa10990d61bec798"
      }
    },
    ...
  ]
   */

  fun getTags(project: Project): List<String> {
    val settings = TerraformToolProjectSettings.getInstance(project)
    val pair = fetchTagsSafe(settings.state?.myLastMetadataETag, settings.state?.myLastMetadataVersions)

    if (pair.first != null) {
      val tags = pair.second
      if (tags != null) {
        val list = tags.sortedWith(Comparator { o1, o2 -> VersionComparatorUtil.compare(o1, o2) })
        settings?.state?.myLastMetadataETag = pair.first
        settings?.state?.myLastMetadataVersions = list
        return list
      } else {
        settings?.state?.myLastMetadataETag = null
      }
    }
    return settings.state?.myLastMetadataVersions ?: emptyList<String>()
  }

  fun download(tag: String, cache: File): File {
    val dst = File(cache, tag)
    if (dst.exists() && dst.isDirectory) {
      return dst
    }
    dst.mkdirs()

    val zip = File(cache, "archives/$tag.zip")
    zip.parentFile.mkdirs()

    try {
      downloadZip(getSrcUrl(tag), zip)
    } catch(e: DownloadException) {
      LOG.warn("Failed to download: ${e.message}")
      return dst
    }

    ZipUtil.extract(zip, dst, null, true)

    return dst
  }


  private fun fetchTagsSafe(etag: String? = null, previous: List<String>? = null): Pair<String?, List<String>?> {
    try {
      return fetchTags(etag, previous)
    } catch(e: DownloadException) {
      LOG.warn("Failed to download tags")
      return null to previous
    }
  }

  @Throws(DownloadException::class)
  private fun fetchTags(etag: String? = null, previous: List<String>? = null): Pair<String, List<String>?> {
    val source: String = TAGS_SRC

    val client: CloseableHttpClient
    try {
      val sslContext = SSLContexts.custom().loadTrustMaterial(null, TrustSelfSignedStrategy()).build()
      val sslFactory = SSLConnectionSocketFactory(sslContext, SSLConnectionSocketFactory.BROWSER_COMPATIBLE_HOSTNAME_VERIFIER)
      client = HttpClientBuilder.create()
          .setRedirectStrategy(DefaultRedirectStrategy())
          .setRetryHandler(DefaultHttpRequestRetryHandler())
          .setSSLSocketFactory(sslFactory).build()
    } catch (e: Throwable) {
      throw DownloadException("Failed to configure client for downloading " + source + ": " + e.message, e)
    }


    try {
      assert(client != null)
      val get: HttpRequestBase = HttpGet(source)
      if (etag != null && previous != null) {
        get.setHeader(HttpHeaders.IF_NONE_MATCH, etag)
      }
      val resp: CloseableHttpResponse = HttpUtil.execute(client, get)

      val statusLine = resp.statusLine
      val code = statusLine.statusCode

      if (code == HttpStatus.SC_NOT_MODIFIED) {
        return etag!! to previous
      }
      if (code != HttpStatus.SC_OK) {
        throw DownloadException("Failed to download '$source': server returned $statusLine")
      }

      @Suppress("NAME_SHADOWING")
      val etag = resp.getFirstHeader("ETag")!!.value
      val stream = resp.entity.content
      val fetched = getAllTags(stream)
      LOG.info("Successfully downloaded $source")
      return etag to fetched
    } catch (e: DownloadException) {
      throw e
    } catch (e: Throwable) {
      throw DownloadException("Failed to download " + source + ": " + e.message, e)
    } finally {
      try {
        client.close()
      } catch (e: Exception) {
      }
    }
  }

  @Throws(DownloadException::class)
  private fun downloadZip(source: String, dest: File): HttpResponse {
    LOG.info(StringBuilder("Downloading ").append(source).append(" to ").append(dest.absolutePath).append("...").toString())

    dest.parentFile.mkdirs()

    val client: CloseableHttpClient
    try {
      val sslContext = SSLContexts.custom().loadTrustMaterial(null, TrustSelfSignedStrategy()).build()
      val sslFactory = SSLConnectionSocketFactory(sslContext, SSLConnectionSocketFactory.BROWSER_COMPATIBLE_HOSTNAME_VERIFIER)
      client = HttpClientBuilder.create()
          .setRedirectStrategy(DefaultRedirectStrategy())
          .setRetryHandler(DefaultHttpRequestRetryHandler())
          .setSSLSocketFactory(sslFactory).build()
    } catch (e: Throwable) {
      throw DownloadException("Failed to configure client for downloading " + source + ": " + e.message, e)
    }

    try {
      val get: HttpGet = HttpGet(source)
      val resp: CloseableHttpResponse = HttpUtil.execute(client, get)

      val statusLine = resp.statusLine
      val code = statusLine.statusCode

      if (code != HttpStatus.SC_OK) {
        throw DownloadException("Failed to download $source: server returned $statusLine")
      }

      val contentLength = resp.entity.contentLength

      var os: OutputStream? = null
      try {
        os = BufferedOutputStream(FileOutputStream(dest))
        resp.entity.writeTo(os)
      } finally {
        try {
          os?.close()
        } catch(e: Exception) {
        }
      }

      if (contentLength > 0 && dest.length() != contentLength) {
        throw DownloadException("Failed to download " + source + ": expected " + contentLength + " bytes, but received " + dest.length() + " bytes")
      }

      LOG.info(StringBuilder("Successfully downloaded ").append(source).append(" to ").append(dest.absolutePath).toString())
      return resp
    } catch (e: DownloadException) {
      throw e
    } catch (e: Throwable) {
      throw DownloadException("Failed to download " + source + ": " + e.message, e)
    } finally {
      try {
        client?.close()
      } catch(e: Exception) {
      }
    }

  }

  private fun getAllTags(stream: InputStream): List<String>? {
    val application = ApplicationManager.getApplication()
    val json: JsonArray<JsonObject>?
    try {
      json = stream.use {
        val parser = Parser()
        @Suppress("UNCHECKED_CAST")
        parser.parse(stream) as JsonArray<JsonObject>?
      }
      if (json == null) {
        logErrorAndFailInInternalMode(application, "No JSON found in input stream")
        return null
      }
    } catch(e: Exception) {
      logErrorAndFailInInternalMode(application, "Failed to load json data from stream", e)
      return null
    }
    return json.map { it.string("name") }.filterNotNull()
  }

  private fun logErrorAndFailInInternalMode(application: Application, msg: String, e: Throwable? = null) {
    val msg2 = if (e == null) msg else "$msg: ${e.message}"
    if (e == null) LOG.error(msg2) else LOG.error(msg2, e)
    if (application.isInternal) {
      throw AssertionError(msg2, e)
    }
  }


  class DownloadException : RuntimeException {
    constructor(message: String, cause: Throwable) : super(message, cause)

    constructor(message: String) : super(message)
  }
}