"""
Allowed values. Must stay identical to
com.cardamage.core.pipeline.ResponseFormatValidator (Java) and to
src/main/resources/schema/damage-assessment-schema.json.

Damage types follow the SYNDCAR dataset (broken glass, broken lights,
cracks, scratches). Parts are a coarse grouping of the 28 SYNDCAR parts
(see convert_syndcar.py: PART_RULES).
"""

DAMAGE_TYPES = ["glass_shatter", "lamp_broken", "crack", "scratch"]
PARTS = ["bumper", "door", "light", "window", "windshield", "hood",
         "fender", "mirror", "wheel", "other"]
SEVERITIES = ["minor", "moderate", "severe"]
ACTIONS = ["repair", "replacement"]

LABEL_FIELDS = {"part": PARTS, "severity": SEVERITIES, "action": ACTIONS}
