"""
Allowed values for the labeled fields. Must stay identical to
com.cardamage.core.pipeline.ResponseFormatValidator (Java) and to
src/main/resources/schema/damage-assessment-schema.json.
"""

DAMAGE_TYPES = ["dent", "scratch", "crack", "glass_shatter", "tire_flat", "lamp_broken"]
PARTS = ["bumper", "door", "headlight", "window", "hood",
         "fender", "mirror", "wheel", "windshield", "other"]
SEVERITIES = ["minor", "moderate", "severe"]
ACTIONS = ["repair", "replacement"]

LABEL_FIELDS = {"part": PARTS, "severity": SEVERITIES, "action": ACTIONS}
