/**
 * JavaScript side of the event stream. The native module forwards every SDK callback through one
 * codegen event emitter; this registry fans it out to `QartveloAds.addListener()` subscribers.
 *
 * The native subscription exists only while at least one JS listener is registered, and every
 * subscription is removable exactly once, so screens that subscribe in `useEffect` never leak.
 */
import type { EventSubscription } from 'react-native';
import { QartveloAdsError } from './errors';
import type { NativeAdEvent, Spec } from './NativeQartveloAds';
import type {
  QartveloAdsEvent,
  QartveloAdsEventListener,
  QartveloAdsEventType,
  QartveloAdsSubscription,
} from './types';
import { EVENT_TYPES, isEventType, toEvent } from './wire';

type Entry = { readonly listener: (event: QartveloAdsEvent) => void };

export class EventRegistry {
  private readonly entries = new Map<QartveloAdsEventType, Set<Entry>>();
  private nativeSubscription: EventSubscription | null = null;

  /** `getNative` returns null on platforms without the native module; listeners are then inert. */
  constructor(private readonly getNative: () => Spec | null) {}

  add<T extends QartveloAdsEventType>(
    type: T,
    listener: QartveloAdsEventListener<T>
  ): QartveloAdsSubscription {
    if (!isEventType(type)) {
      throw new QartveloAdsError(
        'invalid_argument',
        `Unknown event "${String(type)}". Expected one of: ${EVENT_TYPES.join(', ')}`
      );
    }
    if (typeof listener !== 'function') {
      throw new QartveloAdsError(
        'invalid_argument',
        'listener must be a function'
      );
    }
    // A fresh entry per call: adding the same function twice yields two independent subscriptions.
    const entry: Entry = {
      listener: listener as (event: QartveloAdsEvent) => void,
    };
    let set = this.entries.get(type);
    if (!set) {
      set = new Set();
      this.entries.set(type, set);
    }
    set.add(entry);
    this.syncNativeSubscription();

    let removed = false;
    return {
      remove: () => {
        if (removed) {
          return;
        }
        removed = true;
        const current = this.entries.get(type);
        if (current) {
          current.delete(entry);
          if (current.size === 0) {
            this.entries.delete(type);
          }
        }
        this.syncNativeSubscription();
      },
    };
  }

  /** Removes every listener of `type`, or every listener when `type` is omitted. */
  removeAll(type?: QartveloAdsEventType): void {
    if (type === undefined) {
      this.entries.clear();
    } else {
      this.entries.delete(type);
    }
    this.syncNativeSubscription();
  }

  listenerCount(type?: QartveloAdsEventType): number {
    if (type !== undefined) {
      return this.entries.get(type)?.size ?? 0;
    }
    let total = 0;
    this.entries.forEach((set) => {
      total += set.size;
    });
    return total;
  }

  private syncNativeSubscription(): void {
    const wanted = this.entries.size > 0;
    if (wanted && !this.nativeSubscription) {
      const native = this.getNative();
      if (native) {
        this.nativeSubscription = native.onAdEvent((raw) => this.dispatch(raw));
      }
    } else if (!wanted && this.nativeSubscription) {
      const subscription = this.nativeSubscription;
      this.nativeSubscription = null;
      subscription.remove();
    }
  }

  private dispatch(raw: NativeAdEvent): void {
    const event = toEvent(raw);
    if (!event) {
      return;
    }
    const set = this.entries.get(event.type);
    if (!set) {
      return;
    }
    // Snapshot: listeners may unsubscribe (or subscribe) while being notified.
    for (const entry of [...set]) {
      if (!set.has(entry)) {
        continue;
      }
      try {
        entry.listener(event);
      } catch (error) {
        // One faulty listener must not starve the others.
        console.error(`[QartveloAds] "${event.type}" listener threw`, error);
      }
    }
  }
}
