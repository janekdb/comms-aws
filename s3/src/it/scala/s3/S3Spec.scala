package com.ovoenergy.comms.aws
package s3

import model._
import common.{IntegrationSpec, CredentialsProvider}
import common.model._

import java.nio.file.Files
import java.util.UUID

import cats.implicits._
import cats.effect.IO

import fs2._
import fs2.io._
import fs2.Stream.ToEffect

import org.http4s.client._
import blaze.Http1Client
import middleware.{ResponseLogger, RequestLogger}

import scala.concurrent.duration._

class S3Spec extends IntegrationSpec {

  val existingKey = Key("more.pdf")
  val duplicateKey = Key("duplicate")
  val notExistingKey = Key("less.pdf")

  val existingBucket = Bucket("ovo-comms-test")
  val nonExistingBucket = Bucket("ovo-comms-non-existing-bucket")

  val morePdf = IO(getClass.getResourceAsStream("/more.pdf"))
  val moreSize = IO(getClass.getResource("/more.pdf").openConnection().getContentLength)
  val randomKey = IO(Key(UUID.randomUUID().toString))

  implicit class RichToEffectIO[O](te: ToEffect[IO, O]) {
    def lastOrRethrow: IO[O] =
      te.last
        .map(_.toRight[Throwable](new IllegalStateException("Empty Stream")))
        .rethrow

  }

  implicit val patience: PatienceConfig = PatienceConfig(scaled(5.seconds), 500.millis)

  "headObject" when {

    "the bucked exists" when {
      "the key does exist" should {
        val key = existingKey
        "return the object eTag" in withS3 { s3 =>
          s3.headObject(existingBucket, key).futureValue.right.map { os =>
            os.eTag shouldBe Etag("9fe029056e0841dde3c1b8a169635f6f")
          }
        }

        "return the object metadata" in withS3 { s3 =>
          s3.headObject(existingBucket, key).futureValue.right.map { os =>
            os.metadata shouldBe Map("is-test" -> "true")
          }
        }
      }

      "the key does not exist" should {

        "return a Left" in withS3 { s3 =>
          s3.headObject(existingBucket, notExistingKey).futureValue shouldBe a[Left[_, _]]
        }

        "return NoSuchKey error code" in withS3 { s3 =>
          s3.headObject(existingBucket, notExistingKey).futureValue.left.map { error =>
            error.code shouldBe Error.Code("NoSuchKey")
          }
        }

        "return the given key as resource" in withS3 { s3 =>
          s3.headObject(existingBucket, notExistingKey).futureValue.left.map { error =>
            error.key shouldBe notExistingKey.some
          }
        }

      }
    }

    "the bucked does not exist" should {

      "return a Left" in withS3 { s3 =>
        s3.headObject(nonExistingBucket, existingKey).futureValue shouldBe a[Left[_, _]]
      }


      "return NoSuchBucket error code" in withS3 { s3 =>
        s3.headObject(nonExistingBucket, existingKey).futureValue.left.map { error =>
          error.code shouldBe Error.Code("NoSuchBucket")
        }
      }

      "return the given bucket" in withS3 { s3 =>
        s3.headObject(nonExistingBucket, existingKey).futureValue.left.map { error =>
          error.bucketName shouldBe nonExistingBucket.some
        }
      }

    }

  }

  "getObject" when {

    "the bucked exists" when {
      "the key does exist" should {

        "return the object eTag" in checkGetObject(existingBucket, existingKey) { objOrError =>
          objOrError.right.map { obj =>
            obj.summary.eTag shouldBe Etag("9fe029056e0841dde3c1b8a169635f6f")
          }
        }

        "return the object metadata" in checkGetObject(existingBucket, existingKey) { objOrError =>
          objOrError.right.map { obj =>
            obj.summary.metadata shouldBe Map("is-test" -> "true")
          }
        }

        "return the object that can be consumed to a file" in checkGetObject(existingBucket, existingKey) { objOrError =>
          objOrError.right.map { obj =>
            val out = IO(Files.createTempFile("comms-aws", existingKey.value))
              .flatMap { path =>
                IO(Files.newOutputStream(path))
              }
            obj.content.to(writeOutputStream[IO](out)).compile.lastOrRethrow.attempt.futureValue shouldBe a[Right[_, _]]
          }
        }

        "return the object that after been consumed cannot be consumed again" in checkGetObject(existingBucket, existingKey) { objOrError =>
          objOrError.right.map { obj =>
            (obj.content.compile.toList >> obj.content.compile.toList.attempt).futureValue shouldBe a[Left[_, _]]
          }
        }
      }

      "the key does not exist" should {

        "return a Left" in checkGetObject(existingBucket, notExistingKey) { objOrError =>
          objOrError shouldBe a[Left[_, _]]
        }

        "return NoSuchKey error code" in checkGetObject(existingBucket, notExistingKey) { objOrError =>
          objOrError.left.map { error =>
            error.code shouldBe Error.Code("NoSuchKey")
          }
        }

        "return the given key as resource" in checkGetObject(existingBucket, notExistingKey) { objOrError =>
          objOrError.left.map { error =>
            error.key shouldBe notExistingKey.some
          }
        }

      }
    }

    "the bucked does not exist" should {

      "return a Left" in checkGetObject(nonExistingBucket, existingKey) { objOrError =>
        objOrError shouldBe a[Left[_, _]]
      }


      "return NoSuchBucket error code" in checkGetObject(nonExistingBucket, existingKey) { objOrError =>
        objOrError.left.map { error =>
          error.code shouldBe Error.Code("NoSuchBucket")
        }
      }

      "return the given bucket" in checkGetObject(nonExistingBucket, existingKey) { objOrError =>
        objOrError.left.map { error =>
          error.bucketName shouldBe nonExistingBucket.some
        }
      }

    }

  }

  "putObject" when {
    "the bucked exists" when {
      "the key does not exist" should {

        "upload the object content" in withS3 { s3 =>

          val contentIo: IO[ObjectContent[IO]] = moreSize.map { size =>
            ObjectContent(
              readInputStream(morePdf, chunkSize = 64 * 1024),
              size,
              chunked = true
            )
          }

          (for {
            key <- randomKey
            content <- contentIo
            result <- s3.putObject(existingBucket, key, content)
          } yield result).futureValue shouldBe a[Right[_, _]]

        }

        "upload the object content with custom metadata" in withS3 { s3 =>

          val expectedMetadata = Map("test" -> "yes")

          val contentIo: IO[ObjectContent[IO]] = moreSize.map { size =>
            ObjectContent(
              readInputStream(morePdf, chunkSize = 64 * 1024),
              size,
              chunked = true
            )
          }

          (for {
            key <- randomKey
            content <- contentIo
            _ <- s3.putObject(existingBucket, key, content, expectedMetadata)
            summary <- s3.headObject(existingBucket, key)
          } yield summary).futureValue.right.map { summary =>
            summary.metadata shouldBe expectedMetadata

          }
        }
      }

      "the key does exist" should {
        "overwrite the existing key" in withS3 { s3 =>

          val content = ObjectContent.fromByteArray[IO](Array.fill(128 * 1026)(0: Byte))
          (for {
            _ <- s3.putObject(existingBucket, duplicateKey, content)
            result <- s3.putObject(existingBucket, duplicateKey, content)
          } yield result).futureValue shouldBe a[Right[_,_]]

        }
      }
    }

    "the bucked does not exist" should {

      "return a Left" in withS3 { s3 =>
        s3.putObject(nonExistingBucket, existingKey, ObjectContent.fromByteArray(Array.fill(128 * 1026)(0: Byte))).futureValue shouldBe a[Left[_,_]]
      }


      "return NoSuchBucket error code" in withS3 { s3 =>
        s3.putObject(nonExistingBucket, existingKey, ObjectContent.fromByteArray(Array.fill(128 * 1026)(0: Byte))).futureValue.left.map { error =>
          error.bucketName shouldBe nonExistingBucket.some

        }
      }
    }
  }

  def checkGetObject[A](bucket: Bucket, key: Key)(f: Either[Error, Object[IO]] => A):A = withS3 { s3 =>
    Stream.bracket(
      s3.getObject(bucket, key))(
      objOrError => Stream.emit(objOrError),
      objOrError => objOrError.fold(_ => IO.unit, _.content.compile.toList.as(()))
    ).map(f).compile.lastOrRethrow.futureValue
  }

  def withS3[A](f: S3[IO] => A): A = {
    Http1Client
      .stream[IO]()
      .map { client =>
        val responseLogger: Client[IO] => Client[IO] = ResponseLogger.apply0[IO](logBody = true, logHeaders = true)
        val requestLogger: Client[IO] => Client[IO] = RequestLogger.apply0[IO](logBody = false, logHeaders = true)
        new S3[IO](requestLogger(responseLogger(client)), CredentialsProvider.default[IO], Region.`eu-west-1`)
      }
      .map(f)
      .compile
      .lastOrRethrow
      .futureValue
  }

}
