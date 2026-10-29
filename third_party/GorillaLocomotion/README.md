# GorillaLocomotion (Another Axiom)

`core/src/main/kotlin/com/hyperxr/questgames/GorillaRig.kt` is a Kotlin port of `Player.cs` from
https://github.com/Another-Axiom/GorillaLocomotion (the movement of Gorilla Tag), MIT License,
Copyright (c) 2021 Another-Axiom. The licence text is in `LICENSE` here and ships in the APK as
`assets/games/GorillaLocomotion-LICENSE.txt`.

What is the original: the whole `Update()` (hands sticking where they touch, the body moving
instead, averaging two hands, the head check, the jump from the average body speed, unsticking),
`IterativeCollisionSphereCast`, `CollisionsSphereCast`, `StoreVelocities`, the arm length clamp and
the sample tuning (maxArmLength 1.5, unStickDistance 1, minimumRaycastDistance 0.05,
defaultSlideFactor 0.03, defaultPrecision 0.995).

What HyperMagic adds, because there is no Unity: `World.sphereCast` / `raycast` (Unity's
Physics.SphereCast and Raycast, done by sphere tracing over boxes, trunks, capsules and the
ground, ignoring shapes the cast starts inside, as Unity does), the Rigidbody step (gravity, the
head and body colliders resting on things, friction), and `NO_GRIP` surfaces (slip >= 1: they stop
a hand but give it nothing to hold, used by the Escalada de Cano glass walls). The values of
velocityLimit, maxJumpSpeed, jumpMultiplier and velocityHistorySize are not in the repository
(they are set in the Unity scene); the ones used are those of the published sample setups.
