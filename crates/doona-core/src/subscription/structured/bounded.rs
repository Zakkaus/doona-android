//! A YAML or JSON tree built under a budget. serde_yaml bounds how often a document may use an
//! alias, not what the uses build: an anchor of N values used M times builds N × M values from a
//! body of N + M items. [`Bounded`] builds the same `serde_yaml::Value` as its `Deserialize` impl
//! (serde_yaml 0.9.34 `value/de.rs`, a duplicate key and an empty tag refused alike) and counts
//! every value and every string byte it builds, a replayed alias again, so the tree stays within
//! what the body itself could describe.

use serde::de::{self, DeserializeSeed, EnumAccess, MapAccess, SeqAccess, VariantAccess, Visitor};
use serde_yaml::value::{Tag, TaggedValue};
use serde_yaml::{Mapping, Value};
use std::cell::Cell;
use std::fmt;

/// What a tree may still hold.
pub(super) struct Budget {
    values: Cell<usize>,
    bytes: Cell<usize>,
}

impl Budget {
    pub(super) fn new(values: usize, bytes: usize) -> Budget {
        Budget {
            values: Cell::new(values),
            bytes: Cell::new(bytes),
        }
    }
}

#[derive(Clone, Copy)]
pub(super) struct Bounded<'a>(pub &'a Budget);

impl Bounded<'_> {
    fn take<E: de::Error>(self, bytes: usize) -> Result<(), E> {
        let values = self.0.values.get().checked_sub(1);
        let bytes = self.0.bytes.get().checked_sub(bytes);
        let (Some(values), Some(bytes)) = (values, bytes) else {
            return Err(E::custom("the document builds more than its budget"));
        };
        self.0.values.set(values);
        self.0.bytes.set(bytes);
        Ok(())
    }
}

impl<'de> DeserializeSeed<'de> for Bounded<'_> {
    type Value = Value;

    fn deserialize<D: de::Deserializer<'de>>(self, deserializer: D) -> Result<Value, D::Error> {
        deserializer.deserialize_any(self)
    }
}

impl<'de> Visitor<'de> for Bounded<'_> {
    type Value = Value;

    fn expecting(&self, formatter: &mut fmt::Formatter) -> fmt::Result {
        formatter.write_str("any YAML value")
    }

    fn visit_bool<E: de::Error>(self, b: bool) -> Result<Value, E> {
        self.take(0)?;
        Ok(Value::Bool(b))
    }

    fn visit_i64<E: de::Error>(self, i: i64) -> Result<Value, E> {
        self.take(0)?;
        Ok(Value::Number(i.into()))
    }

    fn visit_u64<E: de::Error>(self, u: u64) -> Result<Value, E> {
        self.take(0)?;
        Ok(Value::Number(u.into()))
    }

    fn visit_f64<E: de::Error>(self, f: f64) -> Result<Value, E> {
        self.take(0)?;
        Ok(Value::Number(f.into()))
    }

    fn visit_str<E: de::Error>(self, s: &str) -> Result<Value, E> {
        self.take(s.len())?;
        Ok(Value::String(s.to_owned()))
    }

    fn visit_string<E: de::Error>(self, s: String) -> Result<Value, E> {
        self.take(s.len())?;
        Ok(Value::String(s))
    }

    fn visit_unit<E: de::Error>(self) -> Result<Value, E> {
        self.take(0)?;
        Ok(Value::Null)
    }

    fn visit_none<E: de::Error>(self) -> Result<Value, E> {
        self.visit_unit()
    }

    fn visit_some<D: de::Deserializer<'de>>(self, deserializer: D) -> Result<Value, D::Error> {
        self.deserialize(deserializer)
    }

    fn visit_seq<A: SeqAccess<'de>>(self, mut data: A) -> Result<Value, A::Error> {
        self.take(0)?;
        let mut sequence = Vec::new();
        while let Some(value) = data.next_element_seed(self)? {
            sequence.push(value);
        }
        Ok(Value::Sequence(sequence))
    }

    fn visit_map<A: MapAccess<'de>>(self, mut data: A) -> Result<Value, A::Error> {
        self.take(0)?;
        let mut mapping = Mapping::new();
        while let Some(key) = data.next_key_seed(self)? {
            if mapping.contains_key(&key) {
                return Err(de::Error::custom("duplicate entry in a mapping"));
            }
            let value = data.next_value_seed(self)?;
            mapping.insert(key, value);
        }
        Ok(Value::Mapping(mapping))
    }

    fn visit_enum<A: EnumAccess<'de>>(self, data: A) -> Result<Value, A::Error> {
        self.take(0)?;
        let (tag, contents) = data.variant_seed(TagSeed(self))?;
        let value = contents.newtype_variant_seed(self)?;
        Ok(Value::Tagged(Box::new(TaggedValue { tag, value })))
    }
}

/// A tag, as serde_yaml's `TagStringVisitor` reads it.
struct TagSeed<'a>(Bounded<'a>);

impl<'de> DeserializeSeed<'de> for TagSeed<'_> {
    type Value = Tag;

    fn deserialize<D: de::Deserializer<'de>>(self, deserializer: D) -> Result<Tag, D::Error> {
        deserializer.deserialize_string(self)
    }
}

impl<'de> Visitor<'de> for TagSeed<'_> {
    type Value = Tag;

    fn expecting(&self, formatter: &mut fmt::Formatter) -> fmt::Result {
        formatter.write_str("a YAML tag string")
    }

    fn visit_str<E: de::Error>(self, string: &str) -> Result<Tag, E> {
        self.visit_string(string.to_owned())
    }

    fn visit_string<E: de::Error>(self, string: String) -> Result<Tag, E> {
        if string.is_empty() {
            return Err(E::custom("empty YAML tag is not allowed"));
        }
        self.0.take(string.len())?;
        Ok(Tag::new(string))
    }
}
