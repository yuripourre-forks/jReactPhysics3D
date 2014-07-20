package net.smert.jreactphysics3d.collision;

import java.util.Map;
import net.smert.jreactphysics3d.body.BodyIndexPair;
import net.smert.jreactphysics3d.body.CollisionBody;
import net.smert.jreactphysics3d.body.RigidBody;
import net.smert.jreactphysics3d.collision.broadphase.BodyPair;
import net.smert.jreactphysics3d.collision.broadphase.BroadPhaseAlgorithm;
import net.smert.jreactphysics3d.collision.broadphase.SweepAndPruneAlgorithm;
import net.smert.jreactphysics3d.collision.narrowphase.GJK.GJKAlgorithm;
import net.smert.jreactphysics3d.collision.narrowphase.NarrowPhaseAlgorithm;
import net.smert.jreactphysics3d.collision.narrowphase.SphereVsSphereAlgorithm;
import net.smert.jreactphysics3d.collision.shapes.CollisionShape;
import net.smert.jreactphysics3d.collision.shapes.CollisionShapeType;
import net.smert.jreactphysics3d.constraint.ContactPointInfo;
import net.smert.jreactphysics3d.engine.CollisionWorld;
import net.smert.jreactphysics3d.engine.Profiler;
import net.smert.jreactphysics3d.memory.MemoryAllocator;

/**
 * This class computes the collision detection algorithms. We first perform a broad-phase algorithm to know which pairs
 * of bodies can collide and then we run a narrow-phase algorithm to compute the collision contacts between bodies.
 *
 * @author Jason Sorensen <sorensenj@smert.net>
 */
public class CollisionDetection {

    /// Pointer to the physics world
    private CollisionWorld mWorld;

    /// Memory allocator
    private MemoryAllocator mMemoryAllocator;

    /// Broad-phase overlapping pairs
    private Map<BodyIndexPair, BroadPhasePair> mOverlappingPairs;

    /// Broad-phase algorithm
    private BroadPhaseAlgorithm mBroadPhaseAlgorithm;

    /// Narrow-phase GJK algorithm
    private GJKAlgorithm mNarrowPhaseGJKAlgorithm;

    /// Narrow-phase Sphere vs Sphere algorithm
    private SphereVsSphereAlgorithm mNarrowPhaseSphereVsSphereAlgorithm;

    /// Set of pair of bodies that cannot collide between each other
    private BodyIndexPair mNoCollisionPairs;

    /// Private copy-constructor
    private CollisionDetection(CollisionDetection collisionDetection) {
    }

    /// Private assignment operator
    private CollisionDetection operatorEquals(CollisionDetection collisionDetection) {
        return this;
    }

    /// Compute the broad-phase collision detection
    private void computeBroadPhase() {

        Profiler.startProfilingBlock("CollisionDetection::computeBroadPhase()");

        // Notify the broad-phase algorithm about the bodies that have moved since last frame
        //for (set<CollisionBody*>::iterator it = mWorld.getBodiesBeginIterator();
        for (int it = mWorld.getBodiesBeginIterator(); it != mWorld.getBodiesEndIterator(); it++) {

            // If the body has moved
            if (it.mHasMoved) {

                // Notify the broad-phase that the body has moved
                mBroadPhaseAlgorithm.updateObject(it, it.getAABB());
            }
        }
    }

    /// Compute the narrow-phase collision detection
    private void computeNarrowPhase() {

        Profiler.startProfilingBlock("CollisionDetection::computeNarrowPhase()");

        for (Map.Entry pairs : mOverlappingPairs.entrySet()) {
            ContactPointInfo contactInfo = null;

            BroadPhasePair pair = (BroadPhasePair) pairs.getValue();
            assert (pair != null);

            CollisionBody body1 = pair.body1;
            CollisionBody body2 = pair.body2;

            // Update the contact cache of the overlapping pair
            mWorld.updateOverlappingPair(pair);

            // Check if the two bodies are allowed to collide, otherwise, we do not test for collision
            if (mNoCollisionPairs.count(pair.getBodiesIndexPair()) > 0) {
                continue;
            }

            // Check if the two bodies are sleeping, if so, we do no test collision between them
            if (body1.isSleeping() && body2.isSleeping()) {
                continue;
            }

            // Select the narrow phase algorithm to use according to the two collision shapes
            NarrowPhaseAlgorithm narrowPhaseAlgorithm = SelectNarrowPhaseAlgorithm(
                    body1.getCollisionShape(), body2.getCollisionShape());

            // Notify the narrow-phase algorithm about the overlapping pair we are going to test
            narrowPhaseAlgorithm.setCurrentOverlappingPair(pair);

            // Use the narrow-phase collision detection algorithm to check
            // if there really is a collision
            if (narrowPhaseAlgorithm.testCollision(
                    body1.getCollisionShape(), body1.getTransform(),
                    body2.getCollisionShape(), body2.getTransform(),
                    contactInfo)) {
                assert (contactInfo != null);

                // Set the bodies of the contact
                contactInfo.body1 = (RigidBody) body1;
                contactInfo.body2 = (RigidBody) body2;
                assert (contactInfo.body1 != null);
                assert (contactInfo.body2 != null);

                // Notify the world about the new narrow-phase contact
                mWorld.notifyNewContact(pair, contactInfo);

                // Delete and remove the contact info from the memory allocator
                //contactInfo.ContactPointInfo::~ContactPointInfo();
                //mMemoryAllocator.release(contactInfo, sizeof(ContactPointInfo));
            }
        }
    }

    // Select the narrow-phase collision algorithm to use given two collision shapes
    private NarrowPhaseAlgorithm SelectNarrowPhaseAlgorithm(CollisionShape collisionShape1, CollisionShape collisionShape2) {

        // Sphere vs Sphere algorithm
        if (collisionShape1.getType() == CollisionShapeType.SPHERE && collisionShape2.getType() == CollisionShapeType.SPHERE) {
            return mNarrowPhaseSphereVsSphereAlgorithm;
        } else {   // GJK algorithm
            return mNarrowPhaseGJKAlgorithm;
        }
    }

    // Constructor
    public CollisionDetection(CollisionWorld world, MemoryAllocator memoryAllocator) {
        mWorld = world;
        mMemoryAllocator = memoryAllocator;
        mNarrowPhaseGJKAlgorithm = new GJKAlgorithm(memoryAllocator);
        mNarrowPhaseSphereVsSphereAlgorithm = new SphereVsSphereAlgorithm(memoryAllocator);

        // Create the broad-phase algorithm that will be used (Sweep and Prune with AABB)
        mBroadPhaseAlgorithm = new SweepAndPruneAlgorithm(this);
        assert (mBroadPhaseAlgorithm != null);
    }

    // Add a body to the collision detection
    public void addBody(CollisionBody body) {

        // Add the body to the broad-phase
        mBroadPhaseAlgorithm.addObject(body, body.getAABB());
    }

    // Remove a body from the collision detection
    public void removeBody(CollisionBody body) {

        // Remove the body from the broad-phase
        mBroadPhaseAlgorithm.removeObject(body);
    }

    // Add a pair of bodies that cannot collide with each other
    public void addNoCollisionPair(CollisionBody body1, CollisionBody body2) {
        mNoCollisionPairs.insert(BroadPhasePair.computeBodiesIndexPair(body1, body2));
    }

    // Remove a pair of bodies that cannot collide with each other
    public void removeNoCollisionPair(CollisionBody body1, CollisionBody body2) {
        mNoCollisionPairs.erase(BroadPhasePair.computeBodiesIndexPair(body1, body2));
    }

    // Compute the collision detection
    public void computeCollisionDetection() {

        Profiler.startProfilingBlock("CollisionDetection::computeCollisionDetection()");

        // Compute the broad-phase collision detection
        computeBroadPhase();

        // Compute the narrow-phase collision detection
        computeNarrowPhase();
    }

    // Allow the broadphase to notify the collision detection about an overlapping pair.
    /// This method is called by a broad-phase collision detection algorithm
    public void broadPhaseNotifyAddedOverlappingPair(BodyPair addedPair) {

        // Get the pair of body index
        BodyIndexPair indexPair = addedPair.getBodiesIndexPair();

        // Create the corresponding broad-phase pair object
        BroadPhasePair broadPhasePair = new BroadPhasePair(addedPair.body1, addedPair.body2);
        assert (broadPhasePair != null);

        // Add the pair into the set of overlapping pairs (if not there yet)
        //pair<map<bodyindexpair, BroadPhasePair>::iterator, bool> check = mOverlappingPairs.insert(
        //make_pair(indexPair, broadPhasePair));
        assert (check.second);

        // Notify the world about the new broad-phase overlapping pair
        mWorld.notifyAddedOverlappingPair(broadPhasePair);
    }

    // Allow the broadphase to notify the collision detection about a removed overlapping pair
    public void broadPhaseNotifyRemovedOverlappingPair(BodyPair removedPair) {

        // Get the pair of body index
        BodyIndexPair indexPair = removedPair.getBodiesIndexPair();

        // Get the broad-phase pair
        BroadPhasePair broadPhasePair = mOverlappingPairs.find(indexPair).second;
        assert (broadPhasePair != null);

        // Notify the world about the removed broad-phase pair
        mWorld.notifyRemovedOverlappingPair(broadPhasePair);

        // Remove the overlapping pair from the memory allocator
        //broadPhasePair.BroadPhasePair::~BroadPhasePair();
        //mMemoryAllocator.release(broadPhasePair, sizeof(BroadPhasePair));
        mOverlappingPairs.remove(indexPair);
    }

}
