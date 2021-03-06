package com.evolutiongaming.kafka.journal.util

import cats.effect._
import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._

trait SerialRef[F[_], A] {

  def get: F[A]

  def modify[B](f: A => F[(A, B)]): F[B]

  def update(f: A => F[A]): F[Unit]
}

object SerialRef {

  def of[F[_] : Concurrent, A](value: A): F[SerialRef[F, A]] = {
    for {
      s <- Semaphore[F](1)
      r <- Ref[F].of(value)
    } yield {
      new SerialRef[F, A] {

        def get = r.get

        def modify[B](f: A => F[(A, B)]) = {
          s.withPermit {
            for {
              a <- r.get
              ab <- f(a)
              (a, b) = ab
              _ <- r.set(a)
            } yield b
          }
        }

        def update(f: A => F[A]) = {
          modify { a =>
            for {
              a <- f(a)
            } yield {
              (a, ())
            }
          }
        }
      }
    }
  }
}